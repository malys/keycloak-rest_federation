package com.lyra.idm.keycloak.federation.provider;

import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrLookup;
import org.apache.commons.lang.text.StrSubstitutor;

/***
 * Substitutor based on Properties and Environment
 *
 *  Map valuesMap = HashMap();
 *  valuesMap.put("animal", "quick brown fox");
 *  valuesMap.put("target", "lazy dog");
 *  String templateString = "The ${animal} jumps over the ${target}.";
 */
@JBossLog
public class EnvSubstitutor {

    public static final StrSubstitutor envSubstitutor = new StrSubstitutor(new EnvLookUp());

    private static class EnvLookUp extends StrLookup {

        @Override
        public String lookup(String key) {
            String value;
            {
                value = System.getProperty(key);
                if (StringUtils.isBlank(value)) {
                    value = System.getenv(key);
                }

                if (StringUtils.isBlank(value)) {
                    throw new IllegalArgumentException("key " + key + " is not found in the env variables");
                }
                return value;
            }
        }
    }
}