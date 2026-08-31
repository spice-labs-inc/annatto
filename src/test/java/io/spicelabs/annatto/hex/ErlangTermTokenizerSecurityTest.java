/* Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto.hex;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tokenizer security tests (Fresh Scent Phase 3, finding A4, catalog §7).
 *
 * <p>A hostile-length integer literal previously reached {@code Long.parseLong} in
 * {@link ErlangTermParser} and threw an unchecked {@code NumberFormatException} from an
 * accessor whose contract is {@code ErlangTermException}. The tokenizer now caps integer
 * literals at {@link ErlangTermTokenizer#MAX_INTEGER_DIGITS} digits.
 */
class ErlangTermTokenizerSecurityTest {

    // Requirement: catalog §7 / finding A4 / plan Phase 3.2
    // Theory: literals longer than Long.MAX_VALUE (19 digits) are rejected by the tokenizer
    //         BEFORE Long.parseLong can throw an unchecked NumberFormatException.
    // Boundaries: 19 digits OK, 20 digits rejected.
    // Revert-check: removing the digit cap makes the parser throw NumberFormatException
    //               instead of ErlangTermException.
    @Test
    void oversizedIntegerLiteralRejected() {
        String huge = "9".repeat(ErlangTermTokenizer.MAX_INTEGER_DIGITS + 1);
        assertThatThrownBy(() -> new ErlangTermTokenizer(
                "{<<\"k\">>, " + huge + "}.").tokenize())
                .isInstanceOf(ErlangTermException.class);
    }

    // Requirement: boundary control — exactly 19 digits still tokenizes (no over-rejection).
    @Test
    void maxDigitsIntegerStillAccepted() {
        String ok = "9".repeat(ErlangTermTokenizer.MAX_INTEGER_DIGITS);
        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() ->
                new ErlangTermTokenizer("{<<\"k\">>, " + ok + "}.").tokenize());
    }
}
