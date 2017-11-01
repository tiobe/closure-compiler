/*
 * Copyright 2017 The Closure Compiler Authors.
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

goog.module('jscomp.runtime_tests.polyfill_tests.array_includes_test');
goog.setTestOnly();

const testSuite = goog.require('goog.testing.testSuite');
const userAgent = goog.require('goog.userAgent');

testSuite({
  shouldRunTests() {
    // NOTE: Using ** forces the compiler to see this code as ES7.
    // We need this to guarantee the polyfill is provided.
    // Disable tests for IE8 and below.
    return 1 ** 1 && !userAgent.IE || userAgent.isVersionOrHigher(9);
  },

  testIncludes() {
    let arr = [0, 1, NaN, 3];
    assertTrue(arr.includes(0));
    assertTrue(arr.includes(-0));
    assertTrue(arr.includes(1));
    assertTrue(arr.includes(NaN));
    assertTrue(arr.includes(NaN, 2));
    assertTrue(arr.includes(3));
    assertTrue(arr.includes(3, 3));
    assertFalse(arr.includes(2));
    assertFalse(arr.includes(NaN, 3));
    assertFalse(arr.includes(3, 4));

    arr = 'abcABC';
    assertTrue(Array.prototype.includes.call(arr, 'a'));
    assertFalse(Array.prototype.includes.call(arr, 'd'));

    arr = {length: 2, 0: 5, 1: 6, 2: 7};
    assertTrue(Array.prototype.includes.call(arr, 5));
    assertTrue(Array.prototype.includes.call(arr, 6));
    assertFalse(Array.prototype.includes.call(arr, 5, 1));
    assertFalse(Array.prototype.includes.call(arr, 7));
  },
});
