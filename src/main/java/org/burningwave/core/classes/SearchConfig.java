/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/core
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019-2021 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.core.classes;


import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.burningwave.core.io.FileSystemItem;


@SuppressWarnings("resource")
public class SearchConfig extends SearchConfigAbst<SearchConfig>{
	
	SearchConfig() {
		super();
	}	
	
	public static SearchConfig create() {
		return new SearchConfig(); 
	}
	
	@SafeVarargs
	public static SearchConfig forPaths(Collection<String>... pathsColl) {
		return new SearchConfig().addPaths(pathsColl);
	}
	
	@SafeVarargs
	public static SearchConfig forFileSystemItems(Collection<FileSystemItem>... pathsColl) {
		return new SearchConfig().addFileSystemItems(pathsColl);
	}
	
	@SafeVarargs
	public static SearchConfig forPaths(String... paths) {
		return SearchConfig.forPaths((Collection<String>)Stream.of(paths).collect(Collectors.toCollection(HashSet::new)));
	}
	@SafeVarargs
	public static SearchConfig forResources(String... paths) {
		return forResources(null, paths);
	}
	
	@SafeVarargs
	public static SearchConfig forResources(ClassLoader classLoader, String... paths) {
		return forResources(classLoader, Arrays.asList(paths)); 
	}	
	
	@SafeVarargs
	public static SearchConfig forResources(Collection<String>... pathCollections) {
		return forResources(null, pathCollections);
	}
	
	@SafeVarargs
	public static SearchConfig forResources(ClassLoader classLoader, Collection<String>... pathCollections) {
		return new SearchConfig().addResources(classLoader, pathCollections);
	}
	
	public static SearchConfig byCriteria(ClassCriteria classCriteria) {
		return forPaths(new HashSet<>()).by(classCriteria);
	}
		
	@Override
	SearchConfig newInstance() {
		return new SearchConfig();
	}
	
	
}
