#!/usr/bin/env python3
import sys
import re

matcher = re.compile('(?P<module>.*)/src/test/(java|scala)/(?P<class>.*).(java|scala):@Tag\("functional"\)')

moduleToClassname = {}

for line in sys.stdin:
    matched = matcher.match(line)
    if matched:
        module = matched.group('module')
        className = matched.group('class').replace('/', '.')
        if module not in moduleToClassname:
            moduleToClassname[module] = []
        moduleToClassname[module].append(className)    

print('Creating functional_tests_run.sh.')

for module in moduleToClassname:
    with open ('functional_tests_run.sh', 'a') as rsh:
        command_to_run = "mvn test -DfailIfNoTests=false -DforkCount={} -pl {} -DtrimStackTrace=false -DreuseForks=false -Dcheckstyle.skip=true -Dtest={}".format(len(moduleToClassname[module]), module, ",".join(moduleToClassname[module]))
        rsh.write(command_to_run + '\n')
print('Created functional_tests_run.sh file')