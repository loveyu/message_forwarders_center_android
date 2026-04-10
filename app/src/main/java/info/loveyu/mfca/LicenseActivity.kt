package info.loveyu.mfca

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class LicenseInfo(
    val libraryName: String,
    val licenseName: String,
    val copyright: String,
    val licenseText: String
)

private val openSourceLicenses = listOf(
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

class LicenseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                LicenseScreenContent(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreenContent(
    onBack: () -> Unit
) {
    var selectedLicense by remember { mutableStateOf<LicenseInfo?>(null) }

    if (selectedLicense != null) {
        LicenseDetailView(
            license = selectedLicense!!,
            onBack = { selectedLicense = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("开源许可") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "本应用使用以下开源组件",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "根据各开源组件的许可证，您有权使用、复制、修改和分发这些组件。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "开源组件列表",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(openSourceLicenses) { license ->
                    LicenseCard(
                        license = license,
                        onClick = { selectedLicense = license }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "本应用主程序使用 MIT License",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseCard(
    license: LicenseInfo,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = license.libraryName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = license.licenseName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = license.copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseDetailView(
    license: LicenseInfo,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(license.libraryName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Text(
                    text = license.libraryName,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = license.licenseName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = license.copyright,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = license.licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
