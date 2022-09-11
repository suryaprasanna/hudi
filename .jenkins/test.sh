#!/bin/bash

set -x

export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH

export HUDI_QUIETER_LOGGING=1

CURRENT_VERSION=`mvn help:evaluate -Dexpression=project.version -q -DforceStdout`
SPARK_BUNDLE="packaging/hudi-spark-bundle/target/hudi-spark-bundle_2.11-"$CURRENT_VERSION".jar"

check_jars_correctness()
{
  RES=`jar tf $SPARK_BUNDLE | grep services | grep -v org.apache.spark.sql.sources.DataSourceRegister | wc -l`
  if [ $? -ne 0 ] || [ $RES -ne 1 ]
  then
    echo "Validation for unwanted services failed."
    echo "$RES Unwanted services found in spark bundle"
    return 1
  fi
  echo "Validation for unwanted services is successful."

  # validate no avsc files are included
  RES=`jar tf $SPARK_BUNDLE | grep \.avsc$ | wc -l`
  if [ $? -ne 0 ] || [ $RES -ne 0 ]
  then
    echo "Validation for unwanted avsc files failed."
    echo "$RES Unwanted avsc files found in spark bundle"
    return 1
  fi
  echo "Validation for unwanted avsc files is successful."

  RES=`jar tf $SPARK_BUNDLE | grep \.class$ | grep  ^org/apache/hudi/ -v | grep ^com/uber/hoodie -v | grep ^org/apache/spark -v | wc -l`
  if [ $? -ne 0 ] || [ $RES -ne 0 ]
  then
    echo "Validation for shading classes in spark bundle failed."
    echo "$RES classes are not shaded"
    return 1
  fi
  echo "Validation for shading classes in spark bundle is successful."

  #TODO refactor this code so that it doesn't pollute the main test.sh and have shading tests separate and sourced on demand
  #TODO fix all shell check warnings

  PRESTO_BUNDLE="packaging/hudi-presto-bundle/target/hudi-presto-bundle-$CURRENT_VERSION.jar"
  HIVE_BUNDLE="packaging/hudi-hadoop-mr-bundle/target/hudi-hadoop-mr-bundle-$CURRENT_VERSION.jar"

  JARS_TO_CHECK=("$PRESTO_BUNDLE" "$HIVE_BUNDLE")

  for jar in "${JARS_TO_CHECK[@]}"
  do
    shaded_codahale_num=$(tar tf "$jar"| grep "class$"| grep -c "org/apache/hudi/com/codahale/metrics")
    total_codahale_num=$(tar tf "$jar"| grep "class$"| grep -c "com/codahale/metrics")
    if [ "$shaded_codahale_num" -ne "$total_codahale_num" ]; then
      echo "Validation count for shaded metrics classes for both presto and hive bundles failed."
      return 1
    fi
  done
  echo "Validation count for shaded metrics classes for both presto and hive bundles is successful."

  RES=`find packaging | grep \.jar$ | xargs -n 1 jar tf | grep "org/apache/log4j/.*\\.class" | wc -l`
  if [ $? -ne 0 ] || [ $RES -ne 0 ]
  then
    echo "Across all package .jar files, there are $RES occurrences of class names having 'log4j' in them"
    return 1
  fi
  echo "Validation for zero occurrences of log4j jar is successful."

  return 0
}

# Clean and build
MAVEN_OPTS="-Xmx2g" mvn clean compile test-compile install -DskipTests -DskipITs
if [ $? -eq 0 ]; then
  echo "Build succeeded. Proceeding to run unit tests."
else
  echo "Build failed. Exiting without running unit tests."
  ERR=1
  exit $ERR
fi

# Run unit tests in parallel
mvn test -Punit-tests -DtrimStackTrace=false -DreuseForks=false -Dcheckstyle.skip=true -pl !hudi-client/hudi-spark-client,!hudi-common,!hudi-utilities, -B >log1.txt 2>&1 &
PIDS[0]=$!
mvn test -Punit-tests -DtrimStackTrace=false -DreuseForks=false -Dcheckstyle.skip=true -pl hudi-utilities,hudi-common -B >log2.txt 2>&1 &
PIDS[1]=$!
mvn test -Punit-tests -DtrimStackTrace=false -DreuseForks=false -Dcheckstyle.skip=true -pl hudi-client/hudi-spark-client -B >log3.txt 2>&1 &
PIDS[2]=$!

# Wait for completion
ERR=0
for pid in ${PIDS[*]}; do
  wait $pid
  if [ $? -ne 0 ]; then
    echo "FAILED"
    ERR=1
  fi
done

if [ $ERR -eq 1 ]
then
  # Test output
  echo "Unit tests execution failed."
  cat log1.txt log2.txt log3.txt
  exit $ERR
fi

echo "Successfully completed unit tests in all modules without failures. Proceeding to run functional tests."

grep "@Tag(\"functional\")" -r *| python3 .jenkins/py.py
echo "Starting execution of functional tests."
sh functional_tests_run.sh >log4.txt 2>&1
if [ $? -ne 0 ]; then
  echo "Functional tests execution failed."
  cat log4.txt
  exit 1 
fi
echo "Functional tests ran successfully."

# Aggregate jacoco reports
mvn jacoco:report-aggregate

# Publish to sonarqube  http://localhost:19804/api/system/status
cerberusctl add -s sonarqube-production

find ./ -name *jacoco*

sleep 5
#GIT_BRANCH=`git branch --show-current` doesnt work on jenkins as it does not use branches
GIT_BRANCH="0.10-production"
mvn sonar:sonar -Dsonar.host.url=http://localhost:19804 -Dsonar.login=e74b2552a37f9855fed2c5fe90e252b00ac8c787 \
  -Dsonar.projectKey=org.apache.hudi -Dsonar.scm.exclusions.disabled=true -Dsonar.sourceEncoding=UTF-8 \
  -Dsonar.scm.provider=git -Dsonar.scm.enabled=true -Dsonar.java.binaries=target -Dsonar.verbose=true \
  -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco-ut/jacoco.xml -Dsonar.branch.name=$GIT_BRANCH

if [ $ERR -eq 0 ]
then
  # Check if spark bundle is correctly shaded
  check_jars_correctness
  if [ $? -ne 0 ]
  then
    echo "Validation for jars correctness failed."
    ERR=2
  else
    echo "Validation for jars correctness is successful."
  fi
fi

exit $ERR
