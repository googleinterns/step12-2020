// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.sps.data;

import com.google.common.collect.ImmutableList;

/** Wrapper class for information sent on a search servlet doGet response. */
public class SearchServletResponse {
  private final ImmutableList<Receipt> matchingReceipts;
  private final String encodedCursor;

  public SearchServletResponse(ImmutableList<Receipt> matchingReceipts, String encodedCursor) {
    this.matchingReceipts = ImmutableList.copyOf(matchingReceipts);
    this.encodedCursor = encodedCursor;
  }
}