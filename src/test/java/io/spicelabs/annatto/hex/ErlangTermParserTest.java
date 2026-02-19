/* Copyright 2026 Spice Labs, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.annatto.hex;

import io.spicelabs.annatto.hex.ErlangTermTokenizer.Token;
import io.spicelabs.annatto.hex.ErlangTermTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ErlangTermParser}.
 */
class ErlangTermParserTest {

    private Map<String, Object> parse(String input) {
        List<Token> tokens = new ErlangTermTokenizer(input).tokenize();
        return new ErlangTermParser(tokens).parseMetadataConfig();
    }

    // --- Basic key-value parsing ---

    /**
     * Goal: Parse a single binary string key-value pair.
     * Rationale: Most metadata.config entries are {<<"key">>, <<"value">>}.
     */
    @Test
    void parse_singleStringKeyValue() {
        Map<String, Object> result = parse("{<<\"name\">>,<<\"jason\">>}.");
        assertThat(result).hasSize(1);
        assertThat(result.get("name")).isEqualTo("jason");
    }

    /**
     * Goal: Parse multiple key-value pairs.
     * Rationale: metadata.config has many top-level statements.
     */
    @Test
    void parse_multipleKeyValues() {
        String input = """
                {<<"name">>,<<"jason">>}.
                {<<"version">>,<<"1.4.1">>}.
                """;
        Map<String, Object> result = parse(input);
        assertThat(result).hasSize(2);
        assertThat(result.get("name")).isEqualTo("jason");
        assertThat(result.get("version")).isEqualTo("1.4.1");
    }

    // --- List values ---

    /**
     * Goal: Parse a list of binary strings.
     * Rationale: licenses and build_tools are lists of strings.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_listOfStrings() {
        String input = "{<<\"licenses\">>,[<<\"Apache-2.0\">>]}.";
        Map<String, Object> result = parse(input);
        List<Object> licenses = (List<Object>) result.get("licenses");
        assertThat(licenses).containsExactly("Apache-2.0");
    }

    /**
     * Goal: Parse a list with multiple elements.
     * Rationale: Some fields like build_tools can have multiple entries.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_multiElementList() {
        String input = "{<<\"build_tools\">>,[<<\"mix\">>,<<\"rebar3\">>]}.";
        Map<String, Object> result = parse(input);
        List<Object> tools = (List<Object>) result.get("build_tools");
        assertThat(tools).containsExactly("mix", "rebar3");
    }

    /**
     * Goal: Parse an empty list.
     * Rationale: Some packages may have no dependencies.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_emptyList() {
        String input = "{<<\"requirements\">>,[]}.";
        Map<String, Object> result = parse(input);
        List<Object> reqs = (List<Object>) result.get("requirements");
        assertThat(reqs).isEmpty();
    }

    // --- Boolean values ---

    /**
     * Goal: Parse boolean atom values.
     * Rationale: The optional field in requirements uses true/false.
     */
    @Test
    void parse_booleanValues() {
        String input = "{<<\"optional\">>,true}.";
        Map<String, Object> result = parse(input);
        assertThat(result.get("optional")).isEqualTo(Boolean.TRUE);
    }

    /**
     * Goal: Parse false atom.
     * Rationale: Most deps have optional=false.
     */
    @Test
    void parse_falseAtom() {
        String input = "{<<\"optional\">>,false}.";
        Map<String, Object> result = parse(input);
        assertThat(result.get("optional")).isEqualTo(Boolean.FALSE);
    }

    // --- Integer values ---

    /**
     * Goal: Parse integer values.
     * Rationale: Some metadata may contain integer fields.
     */
    @Test
    void parse_integerValue() {
        String input = "{<<\"count\">>,42}.";
        Map<String, Object> result = parse(input);
        assertThat(result.get("count")).isEqualTo(42L);
    }

    // --- Proplist (list of tuples) → Map conversion ---

    /**
     * Goal: Parse a requirement proplist.
     * Rationale: Q5 — Dependencies are proplists with name, requirement, optional, app, repository.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_requirementProplist() {
        String input = """
                {<<"requirements">>,
                 [[{<<"name">>,<<"decimal">>},
                   {<<"app">>,<<"decimal">>},
                   {<<"optional">>,true},
                   {<<"requirement">>,<<"~> 1.0 or ~> 2.0">>},
                   {<<"repository">>,<<"hexpm">>}]]}.
                """;
        Map<String, Object> result = parse(input);
        List<Object> reqs = (List<Object>) result.get("requirements");
        assertThat(reqs).hasSize(1);

        // The inner proplist should be converted to a Map
        Map<String, Object> dep = (Map<String, Object>) reqs.get(0);
        assertThat(dep.get("name")).isEqualTo("decimal");
        assertThat(dep.get("requirement")).isEqualTo("~> 1.0 or ~> 2.0");
        assertThat(dep.get("optional")).isEqualTo(Boolean.TRUE);
        assertThat(dep.get("app")).isEqualTo("decimal");
        assertThat(dep.get("repository")).isEqualTo("hexpm");
    }

    /**
     * Goal: Parse multiple requirements.
     * Rationale: Most packages have multiple dependencies.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_multipleRequirements() {
        String input = """
                {<<"requirements">>,
                 [[{<<"name">>,<<"dep1">>},{<<"requirement">>,<<">= 0.0.0">>},{<<"optional">>,false},{<<"app">>,<<"dep1">>},{<<"repository">>,<<"hexpm">>}],
                  [{<<"name">>,<<"dep2">>},{<<"requirement">>,<<"~> 1.0">>},{<<"optional">>,false},{<<"app">>,<<"dep2">>},{<<"repository">>,<<"hexpm">>}]]}.
                """;
        Map<String, Object> result = parse(input);
        List<Object> reqs = (List<Object>) result.get("requirements");
        assertThat(reqs).hasSize(2);

        Map<String, Object> dep1 = (Map<String, Object>) reqs.get(0);
        assertThat(dep1.get("name")).isEqualTo("dep1");

        Map<String, Object> dep2 = (Map<String, Object>) reqs.get(1);
        assertThat(dep2.get("name")).isEqualTo("dep2");
    }

    // --- Links (nested proplist) ---

    /**
     * Goal: Parse links as a proplist map.
     * Rationale: links field is {<<"GitHub">>, <<"url">>} tuples.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_linksProplist() {
        String input = """
                {<<"links">>,[{<<"GitHub">>,<<"https://github.com/example">>}]}.
                """;
        Map<String, Object> result = parse(input);
        Map<String, Object> links = (Map<String, Object>) result.get("links");
        assertThat(links.get("GitHub")).isEqualTo("https://github.com/example");
    }

    // --- Empty binary ---

    /**
     * Goal: Parse empty binary string.
     * Rationale: Some fields may have empty string values.
     */
    @Test
    void parse_emptyBinaryString() {
        String input = "{<<\"desc\">>,<<>>}.";
        Map<String, Object> result = parse(input);
        assertThat(result.get("desc")).isEqualTo("");
    }

    // --- Comments ---

    /**
     * Goal: Parse input with comments.
     * Rationale: Some metadata.config files may have comments.
     */
    @Test
    void parse_withComments() {
        String input = """
                % This is a comment
                {<<"name">>,<<"test">>}.
                """;
        Map<String, Object> result = parse(input);
        assertThat(result.get("name")).isEqualTo("test");
    }

    // --- Full real metadata.config ---

    /**
     * Goal: Parse a complete real metadata.config from jason package.
     * Rationale: Validates parser against actual Hex package data.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parse_realJasonMetadata() {
        String input = """
                {<<"links">>,[{<<"GitHub">>,<<"https://github.com/michalmuskala/jason">>}]}.
                {<<"name">>,<<"jason">>}.
                {<<"version">>,<<"1.4.1">>}.
                {<<"description">>,
                 <<"A blazing fast JSON parser and generator in pure Elixir.">>}.
                {<<"elixir">>,<<"~> 1.4">>}.
                {<<"app">>,<<"jason">>}.
                {<<"licenses">>,[<<"Apache-2.0">>]}.
                {<<"requirements">>,
                 [[{<<"name">>,<<"decimal">>},
                   {<<"app">>,<<"decimal">>},
                   {<<"optional">>,true},
                   {<<"requirement">>,<<"~> 1.0 or ~> 2.0">>},
                   {<<"repository">>,<<"hexpm">>}]]}.
                {<<"files">>,
                 [<<"lib">>,<<"lib/jason.ex">>,<<"mix.exs">>,<<"README.md">>]}.
                {<<"build_tools">>,[<<"mix">>]}.
                """;

        Map<String, Object> result = parse(input);

        assertThat(result.get("name")).isEqualTo("jason");
        assertThat(result.get("version")).isEqualTo("1.4.1");
        assertThat(result.get("description")).isEqualTo(
                "A blazing fast JSON parser and generator in pure Elixir.");
        assertThat((List<Object>) result.get("licenses")).containsExactly("Apache-2.0");
        assertThat((List<Object>) result.get("build_tools")).containsExactly("mix");

        List<Object> reqs = (List<Object>) result.get("requirements");
        assertThat(reqs).hasSize(1);
        Map<String, Object> dep = (Map<String, Object>) reqs.get(0);
        assertThat(dep.get("name")).isEqualTo("decimal");
        assertThat(dep.get("optional")).isEqualTo(Boolean.TRUE);
    }

    // --- Error handling ---

    /**
     * Goal: Reject deeply nested input.
     * Rationale: Security — prevent stack overflow via excessive nesting.
     */
    @Test
    void parse_rejectsExcessiveNesting() {
        // Build deeply nested list
        StringBuilder sb = new StringBuilder("{<<\"k\">>,");
        for (int i = 0; i < ErlangTermParser.MAX_NESTING_DEPTH + 2; i++) {
            sb.append("[");
        }
        for (int i = 0; i < ErlangTermParser.MAX_NESTING_DEPTH + 2; i++) {
            sb.append("]");
        }
        sb.append("}.");

        String input = sb.toString();
        assertThatThrownBy(() -> parse(input))
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("Nesting depth exceeds");
    }

    /**
     * Goal: Reject input with too many elements.
     * Rationale: Security — prevent memory exhaustion via massive flat structures.
     */
    @Test
    void parse_rejectsExcessiveElements() {
        // Build a flat list with more than 10000 elements
        StringBuilder sb = new StringBuilder("{<<\"k\">>,[");
        for (int i = 0; i < ErlangTermParser.MAX_ELEMENTS + 1; i++) {
            if (i > 0) sb.append(",");
            sb.append("42");
        }
        sb.append("]}.");

        assertThatThrownBy(() -> parse(sb.toString()))
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("Element count exceeds maximum");
    }

    /**
     * Goal: Reject input with unexpected token.
     * Rationale: Malformed input must produce a clear error.
     */
    @Test
    void parse_rejectsUnexpectedToken() {
        assertThatThrownBy(() -> parse("{<<\"key\">>,}."))
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("Unexpected token");
    }

    /**
     * Goal: Parse empty input.
     * Rationale: Empty metadata.config should produce empty map.
     */
    @Test
    void parse_emptyInput() {
        Map<String, Object> result = parse("");
        assertThat(result).isEmpty();
    }

    // --- Atom values (non-boolean) ---

    /**
     * Goal: Parse nil atom as null.
     * Rationale: Some fields may have nil values.
     */
    @Test
    void parse_nilAtom() {
        String input = "{<<\"value\">>,nil}.";
        Map<String, Object> result = parse(input);
        assertThat(result).containsEntry("value", null);
    }

    /**
     * Goal: Parse bare atom as string.
     * Rationale: Repository field is typically 'hexpm' atom.
     */
    @Test
    void parse_bareAtomAsString() {
        String input = "{<<\"repo\">>,hexpm}.";
        Map<String, Object> result = parse(input);
        assertThat(result.get("repo")).isEqualTo("hexpm");
    }
}
