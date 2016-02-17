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

@Singleton
public class JvmVersionSysCheck extends AbstractSysCheck {

    private final NodeOsJvmExpression jvmExpression;

    private static final int ID = 6;
    private static final int JAVA_VERSION = 8;
    private static final int BUILD_VERSION = 20;
    private static final String DESCRIPTION = "Crate is running with older Java version" +
            "Please update to Java 8 (>= updated 20) runtime environment. ";

    @Inject
    public JvmVersionSysCheck(NodeOsJvmExpression jvmExpression) {
        super(ID, new BytesRef(DESCRIPTION), Severity.MEDIUM);
        this.jvmExpression = jvmExpression;
    }


    @Override
    public boolean validate() {
        return validate(((BytesRef) jvmExpression.getChildImplementation("version").value()).utf8ToString());
    }


    protected boolean validate(String vm_version) {

        int build_version = 1;
        int java_version = 6;

        if(vm_version != null) {
            build_version = Integer.valueOf(vm_version.split("_")[1]);
            java_version = Integer.valueOf(vm_version.split("\\.")[1]);
        }else{
            return false;
        }

        if(java_version < JAVA_VERSION)
            return false;
        else if(build_version < BUILD_VERSION){
            return false;
        }

        return true;
    }


}
