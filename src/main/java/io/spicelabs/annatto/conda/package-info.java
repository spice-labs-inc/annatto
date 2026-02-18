/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Conda ecosystem support for Annatto.
 *
 * <h2>Artifact Formats</h2>
 * <ul>
 *   <li><b>Legacy ({@code .tar.bz2})</b> &mdash; A bzip2-compressed tar archive containing
 *       {@code info/index.json}, {@code info/recipe/meta.yaml}, and package files.</li>
 *   <li><b>Modern ({@code .conda})</b> &mdash; A zip container with zstd-compressed inner
 *       archives: {@code metadata.json} at the top level, plus {@code info-*.tar.zst} and
 *       {@code pkg-*.tar.zst} entries.</li>
 * </ul>
 *
 * <h2>PURL</h2>
 * <p>{@code pkg:conda/channel/name@version}</p>
 *
 * @see <a href="https://docs.conda.io/projects/conda/en/latest/user-guide/concepts/packages.html">Conda Package Concepts</a>
 */
package io.spicelabs.annatto.conda;
