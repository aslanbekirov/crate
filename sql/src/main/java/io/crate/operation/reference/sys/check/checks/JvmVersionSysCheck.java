/*
 * Licensed to Crate.IO GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.reference.sys.check.checks;

import io.crate.metadata.ReferenceImplementation;
import io.crate.operation.reference.sys.node.NodeOsJvmExpression;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.apache.lucene.util.Constants;

import java.util.StringTokenizer;

@Singleton
public class JvmVersionSysCheck extends AbstractSysCheck {

    private static final int ID = 6;
    private static final int MIN_BUILD_VERSION=20;
    private static final String DESCRIPTION = "Crate is running with deprecated Java version" +
            "Please update to Java 8 (>= updated 20) runtime environment. ";

    private static int BUILD_VERSION;
    private static boolean isMinimumJavaVersion;

    public JvmVersionSysCheck() {
        super(ID, new BytesRef(DESCRIPTION), Severity.MEDIUM);
    }

    @Override
    public boolean validate() {
        isMinimumJavaVersion = validateJavaVersion();
        return this.isMinimumJavaVersion;
    }


    protected boolean validateJavaVersion() {
        final StringTokenizer st = new StringTokenizer(Constants.JAVA_VERSION, "_");
        BUILD_VERSION = Integer.parseInt(st.nextToken());

        return Constants.JRE_IS_MINIMUM_JAVA8 && (BUILD_VERSION >= MIN_BUILD_VERSION);
    }


}
