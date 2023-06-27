/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.boot.autoconfigure.condition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Abstract base class for a {@link SpringBootCondition} that also implements
 * {@link AutoConfigurationImportFilter}.
 *
 * @author Phillip Webb
 */
abstract class FilteringSpringBootCondition extends SpringBootCondition
		implements AutoConfigurationImportFilter, BeanFactoryAware, BeanClassLoaderAware {

	private BeanFactory beanFactory;

	private ClassLoader beanClassLoader;

	/**
	 * FilteringSpringBootCondition的match方法主要做的事情还是调用抽象模板方法getOutcomes来根据条件来过滤自动配置类，
	 * 而复写getOutcomes模板方法的有OnClassCondition,OnBeanCondition和OnWebApplicationCondition三个子类
	 */
	@Override
	public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
		// 创建评估报告
		ConditionEvaluationReport report = ConditionEvaluationReport.find(this.beanFactory);
		// 注意getOutcomes是模板方法，将spring.factories文件种加载的所有自动配置类传入
		// 子类（这里指的是OnClassCondition,OnBeanCondition和OnWebApplicationCondition类）去过滤
		// 注意outcomes数组存储的是不匹配的结果，跟autoConfigurationClasses数组一一对应
		/*****************************【主线，重点关注】*********************************************/
		ConditionOutcome[] outcomes = getOutcomes(autoConfigurationClasses, autoConfigurationMetadata);
		boolean[] match = new boolean[outcomes.length];
		// 遍历outcomes,这里outcomes为null则表示匹配，不为null则表示不匹配
		for (int i = 0; i < outcomes.length; i++) {
			match[i] = (outcomes[i] == null || outcomes[i].isMatch());
			if (!match[i] && outcomes[i] != null) {
				// 这里若有某个类不匹配的话，此时调用父类SpringBootCondition的logOutcome方法打印日志
				logOutcome(autoConfigurationClasses[i], outcomes[i]);
				// 并将不匹配情况记录到report
				if (report != null) {
					report.recordConditionEvaluation(autoConfigurationClasses[i], this, outcomes[i]);
				}
			}
		}
		return match;
	}

	protected abstract ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses,
			AutoConfigurationMetadata autoConfigurationMetadata);

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	protected final BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	protected final ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected final List<String> filter(Collection<String> classNames, ClassNameFilter classNameFilter,
			ClassLoader classLoader) {
		if (CollectionUtils.isEmpty(classNames)) {
			return Collections.emptyList();
		}
		List<String> matches = new ArrayList<>(classNames.size());
		for (String candidate : classNames) {
			if (classNameFilter.matches(candidate, classLoader)) {
				matches.add(candidate);
			}
		}
		return matches;
	}

	/**
	 * Slightly faster variant of {@link ClassUtils#forName(String, ClassLoader)} that
	 * doesn't deal with primitives, arrays or inner types.
	 * @param className the class name to resolve
	 * @param classLoader the class loader to use
	 * @return a resolved class
	 * @throws ClassNotFoundException if the class cannot be found
	 */
	protected static Class<?> resolve(String className, ClassLoader classLoader) throws ClassNotFoundException {
		if (classLoader != null) {
			return Class.forName(className, false, classLoader);
		}
		return Class.forName(className);
	}

	protected enum ClassNameFilter {

		/**
		 * 	这里表示指定的类存在于类路径中，则返回true
		 */
		PRESENT {

			@Override
			public boolean matches(String className, ClassLoader classLoader) {
				return isPresent(className, classLoader);
			}

		},

		/**
		 * 	这里表示指定的类不存在于类路径中，则返回true
		 */
		MISSING {

			@Override
			public boolean matches(String className, ClassLoader classLoader) {
				return !isPresent(className, classLoader);
			}

		};

		// 这又是一个抽象方法，分别被PRESENT和MISSING枚举类实现
		abstract boolean matches(String className, ClassLoader classLoader);

		// 检查指定的类是否存在于类路径中
		static boolean isPresent(String className, ClassLoader classLoader) {
			if (classLoader == null) {
				classLoader = ClassUtils.getDefaultClassLoader();
			}
			// 利用类加载器去加载相应类，若没有抛出异常则说明类路径中存在该类，此时返回true
			try {
				resolve(className, classLoader);
				return true;
			}
			catch (Throwable ex) {
				// 若不存在于类路径中，此时抛出的异常将catch住，返回false。
				return false;
			}
		}

	}

}
