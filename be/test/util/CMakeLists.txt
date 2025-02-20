# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# where to put generated libraries
set(EXECUTABLE_OUTPUT_PATH "${BUILD_DIR}/test/util")

ADD_BE_TEST(bit_util_test)
ADD_BE_TEST(brpc_stub_cache_test)
ADD_BE_TEST(path_trie_test)
ADD_BE_TEST(count_down_latch_test)
ADD_BE_TEST(coding_test)
ADD_BE_TEST(lru_cache_util_test)
ADD_BE_TEST(filesystem_util_test)
ADD_BE_TEST(internal_queue_test)
ADD_BE_TEST(cidr_test)
ADD_BE_TEST(new_metrics_test)
ADD_BE_TEST(doris_metrics_test)
ADD_BE_TEST(system_metrics_test)
ADD_BE_TEST(string_util_test)
ADD_BE_TEST(core_local_test)
ADD_BE_TEST(types_test)
ADD_BE_TEST(json_util_test)
ADD_BE_TEST(byte_buffer_test2)
ADD_BE_TEST(uid_util_test)
ADD_BE_TEST(arena_test)
ADD_BE_TEST(aes_util_test)
ADD_BE_TEST(md5_test)
ADD_BE_TEST(bitmap_test)
ADD_BE_TEST(faststring_test)
ADD_BE_TEST(rle_encoding_test)
