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
 * Copyright (c) 2019 Roberto Gentili
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

import static org.burningwave.core.assembler.StaticComponentContainer.IterableObjectHelper;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Objects;
import static org.burningwave.core.assembler.StaticComponentContainer.Synchronizer;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.burningwave.core.Component;
import org.burningwave.core.classes.SearchContext.InitContext;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.iterable.Properties;
import org.burningwave.core.iterable.Properties.Event;

import io.github.toolfactory.jvm.util.Strings;

public interface ClassPathScanner<I, R extends SearchResult<I>> {
	
	public static class Configuration {
		public static class Key {
			
			public final static String DEFAULT_CHECK_FILE_OPTIONS = "hunters.default-search-config.check-file-option";		
			public static final String DEFAULT_SEARCH_CONFIG_PATHS = PathHelper.Configuration.Key.PATHS_PREFIX + "hunters.default-search-config.paths";
						
		}
		
		public final static Map<String, Object> DEFAULT_VALUES;
	
		static {
			Map<String, Object> defaultValues = new HashMap<>();
	
			defaultValues.put(
				Key.DEFAULT_SEARCH_CONFIG_PATHS, 
				PathHelper.Configuration.Key.MAIN_CLASS_PATHS_PLACE_HOLDER + PathHelper.Configuration.getPathsSeparator() +
				"${" + PathHelper.Configuration.Key.MAIN_CLASS_PATHS_EXTENSION + "}" + PathHelper.Configuration.getPathsSeparator() + 
				"${" + PathHelper.Configuration.Key.MAIN_CLASS_REPOSITORIES + "}" + PathHelper.Configuration.getPathsSeparator()
			);
			defaultValues.put(
				Key.DEFAULT_CHECK_FILE_OPTIONS,
				"${" + PathScannerClassLoader.Configuration.Key.SEARCH_CONFIG_CHECK_FILE_OPTION + "}"
			);
			
			DEFAULT_VALUES = Collections.unmodifiableMap(defaultValues);
		}
	}
	
	public R find();
	
	//Not cached search
	public R findBy(SearchConfig searchConfig);
	
	static abstract class Abst<I, C extends SearchContext<I>, R extends SearchResult<I>> implements Component {

		PathHelper pathHelper;
		Function<InitContext, C> contextSupplier;
		Function<C, R> resultSupplier;
		Properties config;
		Collection<SearchResult<I>> searchResults;
		String instanceId;
		ClassLoaderManager<PathScannerClassLoader> defaultPathScannerClassLoaderManager;
		
		Abst(
			PathHelper pathHelper,
			Function<InitContext, C> contextSupplier,
			Function<C, R> resultSupplier,
			Object defaultPathScannerClassLoaderOrDefaultPathScannerClassLoaderSupplier,
			Properties config
		) {
			this.pathHelper = pathHelper;
			this.contextSupplier = contextSupplier;
			this.resultSupplier = resultSupplier;
			this.config = config;
			this.searchResults = ConcurrentHashMap.newKeySet();
			instanceId = Objects.getCurrentId(this);
			this.defaultPathScannerClassLoaderManager = new ClassLoaderManager<>(
				defaultPathScannerClassLoaderOrDefaultPathScannerClassLoaderSupplier
			);
			listenTo(config);
		}
		
		@Override
		public <K, V> void processChangeNotification(
			Properties properties, Event event, K key, V newValue,
			V previousValue
		) {
			if (event.name().equals(Event.PUT.name())) {
				if (key instanceof String) {
					String keyAsString = (String)key;
					if (keyAsString.startsWith(getNameInConfigProperties() + ".default-path-scanner-class-loader")) {
						this.defaultPathScannerClassLoaderManager.reset();
					}
				}
			}
		}	
		
		abstract String getNameInConfigProperties();
		
		abstract String getDefaultPathScannerClassLoaderNameInConfigProperties();
		
		abstract String getDefaultPathScannerClassLoaderCheckFileOptionsNameInConfigProperties();

		PathScannerClassLoader getDefaultPathScannerClassLoader(Object client) {
			return defaultPathScannerClassLoaderManager.get(client);
		}
		
		public R find() {
			return findBy(SearchConfig.withoutUsingCache());
		}
		
		//Not cached search
		public R findBy(SearchConfig input) {
			SearchConfigAbst<?> searchConfig = input.isInitialized() ? input : input.createCopy();
			C context = searchConfig.isInitialized() ? searchConfig.getSearchContext() : searchConfig.init(this);
			PathScannerClassLoader pathScannerClassLoader = context.pathScannerClassLoader;
			Collection<FileSystemItem> pathsToBeScanned = searchConfig.getPathsSupplier().apply(pathScannerClassLoader).getValue();
			final FileSystemItem.Criteria allFileFilters = searchConfig.getAllFileFilters();
			Collection<Map.Entry<FileSystemItem, Collection<FileSystemItem>>> classFilesForPath = ConcurrentHashMap.newKeySet();
			IterableObjectHelper.iterateParallelIf(
				pathsToBeScanned, 
				currentScannedPath -> {
					if (!currentScannedPath.isContainer()) {
						throw new IllegalArgumentException(Strings.compile("{} is not a folder or archive", currentScannedPath.getAbsolutePath()));
					}
					AtomicReference<FileSystemItem.Criteria> finalFilter = new AtomicReference<>(allFileFilters);
					AtomicReference<Boolean> loadPathCompletely = new AtomicReference<>();
					FileSystemItem.Criteria pathScannerClassLoaderFiller = null;
					if (searchConfig.getRefreshPathIf().test(currentScannedPath) || 
						!pathScannerClassLoader.hasBeenCompletelyLoaded(currentScannedPath.getAbsolutePath())) {
						if (!searchConfig.isFileFilterExternallySet() &&
							searchConfig.getFilesRetriever() != SearchConfigAbst.FIND_IN_CHILDREN) {
							loadPathCompletely.set(Boolean.TRUE);
						} else {
							loadPathCompletely.set(Boolean.FALSE);
						}
						finalFilter.set(finalFilter.get().and(pathScannerClassLoaderFiller = getPathScannerClassLoaderFiller(pathScannerClassLoader)));
					}
					if (pathScannerClassLoaderFiller == null) {
						classFilesForPath.add(
							new AbstractMap.SimpleImmutableEntry<>(
								currentScannedPath,
								searchConfig.getFilesRetriever().apply(
									searchConfig.getRefreshPathIf().test(currentScannedPath) ?
										currentScannedPath.refresh() : currentScannedPath	,
									finalFilter.get()
								)
							)
						);
					} else {
						Synchronizer.execute(pathScannerClassLoader.instanceId + "_" + currentScannedPath.getAbsolutePath(), () -> {
							if (!pathScannerClassLoader.hasBeenCompletelyLoaded(currentScannedPath.getAbsolutePath())) {
								pathScannerClassLoader.loadedPaths.put(currentScannedPath.getAbsolutePath(), loadPathCompletely.get());
								classFilesForPath.add(
									new AbstractMap.SimpleImmutableEntry<>(
										currentScannedPath,
										searchConfig.getFilesRetriever().apply(
											searchConfig.getRefreshPathIf().test(currentScannedPath) ?
												currentScannedPath.refresh() : currentScannedPath	,
											finalFilter.get()
										)
									)
								);
							}
						});
					}
				},
				item -> item.size() > 1
			);
			IterableObjectHelper.iterateParallelIf(
				classFilesForPath,
				currentScannedPath -> {
					for (FileSystemItem child : currentScannedPath.getValue()) {
						analyzeAndAddItemsToContext(context, child, currentScannedPath.getKey());
					}
				},
				item -> item.size() > 1
			);
			Collection<String> skippedClassesNames = context.getSkippedClassNames();
			if (!skippedClassesNames.isEmpty()) {
				ManagedLoggersRepository.logWarn(getClass()::getName, "Skipped classes count: {}", skippedClassesNames.size());
			}
			R searchResult = resultSupplier.apply(context);
			searchResult.setClassPathScanner(this);
			return searchResult;
		}
		

		private FileSystemItem.Criteria getPathScannerClassLoaderFiller(PathScannerClassLoader pathScannerClassLoader) {
			return FileSystemItem.Criteria.forAllFileThat(fileSystemItem -> {
				JavaClass javaClass = fileSystemItem.toJavaClass();
				try {
					String className = javaClass.getName();
					if (pathScannerClassLoader.loadedByteCodes.get(className) == null &&
						pathScannerClassLoader.notLoadedByteCodes.get(className) == null) {
						pathScannerClassLoader.addByteCode0(className, javaClass.getByteCode());
					}
				} catch (NullPointerException exc) {
					if (javaClass != null) {
						throw exc;
					}					
				}
				return true;
			});
		}

		
		void analyzeAndAddItemsToContext(C context, FileSystemItem child, FileSystemItem basePath) {
			JavaClass javaClass = child.toJavaClass();
			try {
				ClassCriteria.TestContext criteriaTestContext = testClassCriteria(context, javaClass);
				if (criteriaTestContext.getResult()) {
					addToContext(
						context, criteriaTestContext, basePath.getAbsolutePath(), child, javaClass
					);
				}
			} catch (NullPointerException exc) {
				if (javaClass != null) {
					throw exc;
				}
			}
		}

		
		<S extends SearchConfigAbst<S>> ClassCriteria.TestContext testClassCriteria(C context, JavaClass javaClass) {
			return context.test(context.loadClass(javaClass.getName()));
		}
		
		
		abstract void addToContext(
			C context,
			ClassCriteria.TestContext criteriaTestContext,
			String basePath,
			FileSystemItem currentIteratedFile,
			JavaClass javaClass
		);
		
		boolean register(SearchResult<I> searchResult) {
			searchResults.add(searchResult);
			return true;
		}
		
		boolean unregister(SearchResult<I> searchResult) {
			searchResults.remove(searchResult);
			return true;
		}
		
		public synchronized void closeSearchResults() {
			Collection<SearchResult<I>> searchResults = this.searchResults;
			if (searchResults != null) {
				Iterator<SearchResult<I>> searchResultsIterator = searchResults.iterator();		
				while(searchResultsIterator.hasNext()) {
					SearchResult<I> searchResult = searchResultsIterator.next();
					searchResult.close();
				}
			}
		}
		
		@Override
		public void close() {
			unregister(config);
			pathHelper = null;
			contextSupplier = null;
			config = null;
			closeSearchResults();
			this.searchResults = null;
		}
	}
}