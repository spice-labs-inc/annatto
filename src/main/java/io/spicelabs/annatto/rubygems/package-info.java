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
 * RubyGems ecosystem support for Annatto.
 *
 * <h2>Artifact Format</h2>
 * <p>A {@code .gem} file is a tar archive containing:</p>
 * <ul>
 *   <li>{@code metadata.gz} &mdash; gzip-compressed YAML gemspec with package metadata</li>
 *   <li>{@code data.tar.gz} &mdash; gzip-compressed tar of the actual library source files</li>
 * </ul>
 *
 * <h2>PURL</h2>
 * <p>{@code pkg:gem/name@version}</p>
 *
 * @see <a href="https://guides.rubygems.org/specification-reference/">RubyGems Specification Reference</a>
 */
package io.spicelabs.annatto.rubygems;
