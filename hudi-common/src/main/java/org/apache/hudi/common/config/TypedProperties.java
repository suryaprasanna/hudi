/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.config;

import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Type-aware extension of {@link java.util.Properties}.
 */
public class TypedProperties extends Properties implements Serializable {

  public TypedProperties() {
    super(null);
  }

  protected TypedProperties(Properties defaults) {
    if (Objects.nonNull(defaults)) {
      for (Enumeration<?> e = defaults.propertyNames(); e.hasMoreElements(); ) {
        Object k = e.nextElement();
        Object v = defaults.get(k);
        if (v != null) {
          put(k, v);
        }
      }
    }
  }

  public void setPropertyIfNonNull(String key, Object value) {
    if (value != null) {
      setProperty(key, value.toString());
    }
  }

  @Override
  public String getProperty(String key) {
    Object oval = super.get(key);
    String sval = (oval != null) ? String.valueOf(oval) : null;
    return ((sval == null) && (defaults != null)) ? defaults.getProperty(key) : sval;
  }

  private void checkKey(String property) {
    if (!containsKey(property)) {
      throw new IllegalArgumentException("Property " + property + " not found");
    }
  }

  public String getString(String property) {
    checkKey(property);
    return getProperty(property);
  }

  public String getString(String property, String defaultValue) {
    return containsKey(property) ? getProperty(property) : defaultValue;
  }

  public Option<String> getNonEmptyStringOpt(String property, String defaultValue) {
    return Option.ofNullable(StringUtils.emptyToNull(getString(property, defaultValue)));
  }

  public List<String> getStringList(String property, String delimiter, List<String> defaultVal) {
    if (!containsKey(property)) {
      return defaultVal;
    }
    return Arrays.stream(getProperty(property).split(delimiter)).map(String::trim).filter(s -> !StringUtils.isNullOrEmpty(s)).collect(Collectors.toList());
  }

  public int getInteger(String property) {
    checkKey(property);
    return Integer.parseInt(getProperty(property));
  }

  public int getInteger(String property, int defaultValue) {
    return containsKey(property) ? Integer.parseInt(getProperty(property)) : defaultValue;
  }

  public long getLong(String property) {
    checkKey(property);
    return Long.parseLong(getProperty(property));
  }

  public long getLong(String property, long defaultValue) {
    return containsKey(property) ? Long.parseLong(getProperty(property)) : defaultValue;
  }

  public boolean getBoolean(String property) {
    checkKey(property);
    return Boolean.parseBoolean(getProperty(property));
  }

  public boolean getBoolean(String property, boolean defaultValue) {
    return containsKey(property) ? Boolean.parseBoolean(getProperty(property)) : defaultValue;
  }

  public double getDouble(String property) {
    checkKey(property);
    return Double.parseDouble(getProperty(property));
  }

  public double getDouble(String property, double defaultValue) {
    return containsKey(property) ? Double.parseDouble(getProperty(property)) : defaultValue;
  }

  /**
   * This method is introduced to get rid of the scala compile error:
   * <pre>
   *   <code>
   *   ambiguous reference to overloaded definition,
   *   both method putAll in class Properties of type (x$1: java.util.Map[_, _])Unit
   *   and  method putAll in class Hashtable of type (x$1: java.util.Map[_ <: Object, _ <: Object])Unit
   *   match argument types (java.util.HashMap[Nothing,Nothing])
   *       properties.putAll(new java.util.HashMap())
   *   </code>
   * </pre>
   *
   * @param items The new items to put
   */
  public static TypedProperties fromMap(Map<?, ?> items) {
    TypedProperties props = new TypedProperties();
    props.putAll(items);
    return props;
  }

  /**
   * This method is introduced to get rid of the scala compile error:
   * <pre>
   *   <code>
   *   ambiguous reference to overloaded definition,
   *   both method putAll in class Properties of type (x$1: java.util.Map[_, _])Unit
   *   and  method putAll in class Hashtable of type (x$1: java.util.Map[_ <: Object, _ <: Object])Unit
   *   match argument types (java.util.HashMap[Nothing,Nothing])
   *       properties.putAll(new java.util.HashMap())
   *   </code>
   * </pre>
   *
   * @param props The properties
   * @param items The new items to put
   */
  public static void putAll(TypedProperties props, Map<?, ?> items) {
    props.putAll(items);
  }

  public static TypedProperties copy(Properties defaults) {
    return new TypedProperties(defaults);
  }
}
