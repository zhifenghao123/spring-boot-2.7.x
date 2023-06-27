/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @author Moritz Halbritter
 * @since 1.3.0
 * @see EnableAutoConfiguration
 */
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
		ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {

	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();

	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory.getLog(AutoConfigurationImportSelector.class);

	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

	private ConfigurableListableBeanFactory beanFactory;

	private Environment environment;

	private ClassLoader beanClassLoader;

	private ResourceLoader resourceLoader;

	private ConfigurationClassFilter configurationClassFilter;

	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
		// 会在所有@Configuration类都解析完之后才执行
		if (!isEnabled(annotationMetadata)) {
			return NO_IMPORTS;
		}
		// 获取自动配置类(2.7以前版本是：META-INF/spring.factories，
		// 2.7及以后版本是：META-INF/spring/%s.imports，springboot3之后，是已完全废弃去掉META-INF/spring.factories机制)
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(annotationMetadata);
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	@Override
	public Predicate<String> getExclusionFilter() {
		return this::shouldExclude;
	}

	private boolean shouldExclude(String configurationClassName) {
		return getConfigurationClassFilter().filter(Collections.singletonList(configurationClassName)).isEmpty();
	}

	/**
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 *
	 * 获取符合条件的自动配置类，避免加载不必要的自动配置类从而造成内存浪费
	 * added by haozhifeng
	 */
	protected AutoConfigurationEntry getAutoConfigurationEntry(AnnotationMetadata annotationMetadata) {
		// 获取是否有配置spring.boot.enableautoconfiguration属性，默认返回true
		if (!isEnabled(annotationMetadata)) {
			return EMPTY_ENTRY;
		}
		// 获取@EnableAutoConfiguration的属性，比如exclude，excludeName等等
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// 获取META-INF/spring.factories、META-INF/spring/%s.imports中的AutoConfiguration
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// 利用LinkedHashSet移除重复的配置类,按类名去重
		configurations = removeDuplicates(configurations);
		// 获取需要排出的AutoConfiguration，
		// 可以通过@EnableAutoConfiguration的注解exclude属性，或者在配置文件中指定参数 spring.autoconfigure.exclude进行排除
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// 【2】将要排除的配置类移除
		checkExcludedClasses(configurations, exclusions);
		configurations.removeAll(exclusions);
		// 【3】因为从spring.factories文件获取的自动配置类太多，如果有些不必要的自动配置类都加载进内存，会造成内存浪费，因此这里需要进行过滤
		// 注意这里会获取spring.factories中的AutoConfigurationImportFilter对AutoConfiguration进行过滤，
		// 即调用AutoConfigurationImportFilter的match方法来判断是否符合@ConditionalOnBean,@ConditionalOnClass
		// 或@ConditionalOnWebApplication，这三个会去判断上面的AutoConfiguration是否符合它们自身所要求的条件，不符合的会打印日志
		configurations = getConfigurationClassFilter().filter(configurations);
		// 【4】获取了符合条件的自动配置类后，此时触发AutoConfigurationImportEvent事件，
		// 目的是告诉ConditionEvaluationReport条件评估报告器对象来记录符合条件的自动配置类
		// 该事件什么时候会被触发？--> 在刷新容器时调用invokeBeanFactoryPostProcessors后置处理器时触发
		fireAutoConfigurationImportEvents(configurations, exclusions);
		// 【5】将符合条件和要排除的自动配置类封装进AutoConfigurationEntry对象，并返回
		//  即最终返回的AutoConfiguration都是符合条件的
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	@Override
	public Class<? extends Group> getImportGroup() {
		return AutoConfigurationGroup.class;
	}

	protected boolean isEnabled(AnnotationMetadata metadata) {
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		String name = getAnnotationClass().getName();
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		Assert.notNull(attributes, () -> "No auto-configuration attributes found. Is " + metadata.getClassName()
				+ " annotated with " + ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	protected Class<?> getAnnotationClass() {
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default
	 * this method will load candidates using {@link ImportCandidates} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}. For backward compatible reasons it
	 * will also consider {@link SpringFactoriesLoader} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 */
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		List<String> configurations = new ArrayList<>(
				SpringFactoriesLoader.loadFactoryNames(getSpringFactoriesLoaderFactoryClass(), getBeanClassLoader()));
		ImportCandidates.load(AutoConfiguration.class, getBeanClassLoader()).forEach(configurations::add);
		Assert.notEmpty(configurations,
				"No auto configuration classes found in META-INF/spring.factories nor in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports. If you "
						+ "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	/**
	 * Return the class used by {@link SpringFactoriesLoader} to load configuration
	 * candidates.
	 * @return the factory class
	 */
	protected Class<?> getSpringFactoriesLoaderFactoryClass() {
		return EnableAutoConfiguration.class;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		for (String exclusion : exclusions) {
			if (ClassUtils.isPresent(exclusion, getClass().getClassLoader()) && !configurations.contains(exclusion)) {
				invalidExcludes.add(exclusion);
			}
		}
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String.format(
				"The following classes could not be excluded because they are not auto-configuration classes:%n%s",
				message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		excluded.addAll(asList(attributes, "exclude"));
		excluded.addAll(asList(attributes, "excludeName"));
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		return excluded;
	}

	/**
	 * Returns the auto-configurations excluded by the
	 * {@code spring.autoconfigure.exclude} property.
	 * @return excluded auto-configurations
	 * @since 2.3.2
	 */
	protected List<String> getExcludeAutoConfigurationsProperty() {
		Environment environment = getEnvironment();
		if (environment == null) {
			return Collections.emptyList();
		}
		if (environment instanceof ConfigurableEnvironment) {
			Binder binder = Binder.get(environment);
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class).map(Arrays::asList)
					.orElse(Collections.emptyList());
		}
		String[] excludes = environment.getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}

	private ConfigurationClassFilter getConfigurationClassFilter() {
		if (this.configurationClassFilter == null) {
			List<AutoConfigurationImportFilter> filters = getAutoConfigurationImportFilters();
			for (AutoConfigurationImportFilter filter : filters) {
				invokeAwareMethods(filter);
			}
			this.configurationClassFilter = new ConfigurationClassFilter(this.beanClassLoader, filters);
		}
		return this.configurationClassFilter;
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList(value);
	}

	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			for (AutoConfigurationImportListener listener : listeners) {
				invokeAwareMethods(listener);
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}

	private void invokeAwareMethods(Object instance) {
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) instance).setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware) {
				((BeanFactoryAware) instance).setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware) {
				((EnvironmentAware) instance).setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware) {
				((ResourceLoaderAware) instance).setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private static class ConfigurationClassFilter {

		private final AutoConfigurationMetadata autoConfigurationMetadata;

		private final List<AutoConfigurationImportFilter> filters;

		ConfigurationClassFilter(ClassLoader classLoader, List<AutoConfigurationImportFilter> filters) {
			this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(classLoader);
			this.filters = filters;
		}

		/**
		 * AutoConfigurationImportSelector的filter方法主要做的事情就是调用AutoConfigurationImportFilter接口的match方法来
		 * 判断每一个自动配置类上的条件注解（若有@ConditionalOnClass,@ConditionalOnBean或@ConditionalOnWebApplication的话）是否满足条件，
		 * 若满足，则返回true，说明匹配，若不满足，则返回false说明不匹配。
		 */
		List<String> filter(List<String> configurations) {
			long startTime = System.nanoTime();
			// 将从spring.factories中获取的自动配置类转出字符串数组
			String[] candidates = StringUtils.toStringArray(configurations);
			// 是否需要跳过标识
			boolean skipped = false;
			// getAutoConfigurationImportFilters方法：拿到OnBeanCondition，OnClassCondition和OnWebApplicationCondition
			// 然后遍历这三个条件类去过滤从spring.factories加载的大量配置类
			for (AutoConfigurationImportFilter filter : this.filters) {
				/**
				 * AutoConfigurationImportFilter接口有一个具体的实现类FilteringSpringBootCondition，
				 * FilteringSpringBootCondition又有三个具体的子类：OnClassCondition,OnBeanCondtition和OnWebApplicationCondition
				 *
				 * FilteringSpringBootCondition实现了AutoConfigurationImportFilter接口的match方法，
				 * 然后在FilteringSpringBootCondition的match方法调用getOutcomes这个抽象模板方法返回自动配置类的匹配与否的信息。
				 * 同时，最重要的是FilteringSpringBootCondition的三个子类OnClassCondition,OnBeanCondtition
				 * 和OnWebApplicationCondition将会复写这个模板方法实现自己的匹配判断逻辑。
				 */
				// 判断各种filter来判断每个candidate（这里实质要通过candidate(自动配置类)拿到其标注的
				// @ConditionalOnClass,@ConditionalOnBean和@ConditionalOnWebApplication里面的注解值）是否匹配，
				// 注意candidates数组与match数组一一对应
				/**********************【主线，重点关注】********************************/
				boolean[] match = filter.match(candidates, this.autoConfigurationMetadata);
				// 遍历match数组，注意match顺序跟candidates的自动配置类一一对应
				for (int i = 0; i < match.length; i++) {
					// 若有不匹配的话
					if (!match[i]) {
						// 因为不匹配，将相应的自动配置类置空
						candidates[i] = null;
						// 标注skipped为true
						skipped = true;
					}
				}
			}
			// 这里表示若所有自动配置类经过OnBeanCondition，OnClassCondition和OnWebApplicationCondition过滤后，全部都匹配的话，则全部原样返回
			if (!skipped) {
				return configurations;
			}
			// 建立result集合来装匹配的自动配置类
			List<String> result = new ArrayList<>(candidates.length);
			for (String candidate : candidates) {
				// 若相应的自动配置类置不为空，则表示都是匹配的，此时添加到result集合中
				if (candidate != null) {
					result.add(candidate);
				}
			}
			// 打印日志
			if (logger.isTraceEnabled()) {
				int numberFiltered = configurations.size() - result.size();
				logger.trace("Filtered " + numberFiltered + " auto configuration class in "
						+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + " ms");
			}
			// 最后返回符合条件的自动配置类
			return result;
		}

	}

	private static class AutoConfigurationGroup
			implements DeferredImportSelector.Group, BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {

		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();

		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		private ClassLoader beanClassLoader;

		private BeanFactory beanFactory;

		private ResourceLoader resourceLoader;

		private AutoConfigurationMetadata autoConfigurationMetadata;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		/**
		 * 在Spring框架（spring-context模块）中的ConfigurationClassParser类中，解析@Import导入DeferredImportSelector实现类逻辑
		 * <pre class="code">
		 * public Iterable<Group.Entry> getImports() {
		 *     // 遍历DeferredImportSelectorHolder对象集合deferredImports，deferredImports集合装了各种ImportSelector，当然这里装的是AutoConfigurationImportSelector
		 *     for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
		 *     	// 【1】，利用AutoConfigurationGroup的process方法来处理自动配置的相关逻辑，决定导入哪些配置类（可以认为是SpringBoot自动配置逻辑的入口）
		 *     	this.group.process(deferredImport.getConfigurationClass().getMetadata(),
		 *     			deferredImport.getImportSelector());
		 *     }
		 *     // 【2】，经过上面的处理后，然后再进行选择导入哪些配置类
		 *     return this.group.selectImports();
		 * }</pre>
		 *
		 *<p>标【1】处的的代码是我们分析的重中之重，自动配置的相关的绝大部分逻辑全在这里。
		 * 那么this.group.process(deferredImport.getConfigurationClass().getMetadata(), deferredImport.getImportSelector())；
		 * 主要做的事情就是在this.group(即AutoConfigurationGroup对象)的process方法中，传入的AutoConfigurationImportSelector
		 * 对象来选择一些符合条件的自动配置类，过滤掉一些不符合条件的自动配置类。
		 * 注：（1）AutoConfigurationGroup：是AutoConfigurationImportSelector的内部类，主要用来处理自动配置相关的逻辑，
		 * 拥有process和selectImports方法，然后拥有entries和autoConfigurationEntries集合属性，这两个集合分别存储被处理后的符合条件的自动配置类；
		 * （2）AutoConfigurationImportSelector：承担自动配置的绝大部分逻辑，负责选择一些符合条件的自动配置类；
		 * （3）metadata：即标注在SpringBoot启动类上的@SpringBootApplication注解元数据
		 *
		 * 标【2】的this.group.selectImports的方法主要是针对前面的process方法处理后的自动配置类再进一步有选择的选择导入
		 *
		 */

		/**
		 * 这里用来处理自动配置类，比如过滤掉不符合匹配条件的自动配置类
		 *
		 * @param annotationMetadata 标注在SpringBoot启动类上的@SpringBootApplication注解元数据
 		 * @param deferredImportSelector 其实就是AutoConfigurationImportSelector
		 * @return:
		 * @author: haozhifeng
		 */
		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			Assert.state(deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
			// 【1】调用getAutoConfigurationEntry方法得到自动配置类放入autoConfigurationEntry对象中
			AutoConfigurationEntry autoConfigurationEntry = ((AutoConfigurationImportSelector) deferredImportSelector)
					.getAutoConfigurationEntry(annotationMetadata);
			// 【2】将封装了自动配置类的autoConfigurationEntry对象装进autoConfigurationEntries集合
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			// 【3】遍历刚获取的自动配置类
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				// 这里符合条件的自动配置类作为key，annotationMetadata作为值放进entries集合
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		/**
		 * selectImports方法主要是针对经过排除掉exclude的和被AutoConfigurationImportFilter接口过滤后的满足条件的自动配置类
		 * 再进一步排除exclude的自动配置类，然后再排序。
		 */
		@Override
		public Iterable<Entry> selectImports() {
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			// 这里得到所有要排除的自动配置类的set集合
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getExclusions).flatMap(Collection::stream).collect(Collectors.toSet());
			// 这里得到经过滤后所有符合条件的自动配置类的set集合
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getConfigurations).flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			// 移除掉要排除的自动配置类
			processedConfigurations.removeAll(allExclusions);

			// 对标注有@Order注解的自动配置类进行排序，
			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
					.map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName))
					.collect(Collectors.toList());
		}

		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
			if (this.autoConfigurationMetadata == null) {
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata)
					.getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME,
						MetadataReaderFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}

	protected static class AutoConfigurationEntry {

		private final List<String> configurations;

		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations, Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}
