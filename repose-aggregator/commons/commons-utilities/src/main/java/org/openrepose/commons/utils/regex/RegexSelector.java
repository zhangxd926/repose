/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.commons.utils.regex;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author zinic
 */
public class RegexSelector<K> {
    private final List<SelectorPattern<K>> compiledPatterns;
    private Pattern lastMatch;

    public RegexSelector() {
        compiledPatterns = new LinkedList<SelectorPattern<K>>();
    }

    public Pattern getLastMatch() {
        return lastMatch;
    }

    public void clear() {
        compiledPatterns.clear();
    }

    public void addPattern(String pattern, K key) {
        compiledPatterns.add(new SelectorPattern<K>(Pattern.compile(pattern), key));
    }

    public SelectorResult<K> select(String selectOn) {
        for (SelectorPattern<K> selector : compiledPatterns) {
            if (selector.matcher(selectOn).matches()) {
                lastMatch = selector.getPattern();
                return new SelectorResult<K>(selector.getKey());
            }
        }

        return SelectorResult.emptyResult();
    }
}
