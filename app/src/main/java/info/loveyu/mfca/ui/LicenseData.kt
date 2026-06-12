package info.loveyu.mfca.ui

data class LicenseInfo(
    val libraryName: String,
    val licenseName: String,
    val copyright: String,
    val licenseText: String
)

val openSourceLicenses = listOf(
    LicenseInfo(
        libraryName = "AndroidX",
        licenseName = "Apache License 2.0",
        copyright = "Copyright (C) 2011-2024 The AndroidX Authors",
        licenseText = """
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "Jetpack Compose",
        licenseName = "Apache License 2.0",
        copyright = "Copyright (C) 2020-2024 The Compose Authors",
        licenseText = """
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "NanoHTTPD",
        licenseName = "BSD-3-Clause",
        copyright = "Copyright (c) 2001-2024 NanoHTTPD Authors",
        licenseText = """
            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions
            are met:

            1. Redistributions of source code must retain the above copyright
               notice, this list of conditions and the following disclaimer.

            2. Redistributions in binary form must reproduce the above copyright
               notice, this list of conditions and the following disclaimer in the
               documentation and/or other materials provided with the distribution.

            3. Neither the name of the copyright holder nor the names of its
               contributors may be used to endorse or promote products derived from
               this software without specific prior written permission.

            THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
            AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
            THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
            PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
            CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
            EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
            PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
            PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
            LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
            NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
            SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "SnakeYAML",
        licenseName = "Apache License 2.0",
        copyright = "Copyright (c) 2008-2024 SnakeYAML Authors",
        licenseText = """
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "Kotlin Coroutines",
        licenseName = "Apache License 2.0",
        copyright = "Copyright (c) 2010-2024 JetBrains s.r.o. and Kotlin Authors",
        licenseText = """
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "Eclipse Paho MQTT",
        licenseName = "Eclipse Public License 2.0",
        copyright = "Copyright (c) 2012-2024 Eclipse Foundation",
        licenseText = """
            This program and the accompanying materials
            are made available under the terms of the Eclipse Public License 2.0
            which accompanies this distribution, and is available at
            https://www.eclipse.org/legal/epl-2.0/

            SPDX-License-Identifier: EPL-2.0

            Contributors:
               Eclipse Foundation - initial API and implementation
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "OkHttp",
        licenseName = "Apache License 2.0",
        copyright = "Copyright (C) 2014-2024 Square, Inc. and OkHttp Authors",
        licenseText = """
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()
    ),
    LicenseInfo(
        libraryName = "JUnit (Test Only)",
        licenseName = "Eclipse Public License 2.0",
        copyright = "Copyright (c) 2002-2024 Eclipse Foundation and JUnit Authors",
        licenseText = """
            This program and the accompanying materials
            are made available under the terms of the Eclipse Public License 2.0
            which accompanies this distribution, and is available at
            https://www.eclipse.org/legal/epl-2.0/

            SPDX-License-Identifier: EPL-2.0

            Contributors:
               Eclipse Foundation - initial API and implementation
        """.trimIndent()
    )
)
