package springboot.hao;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * HaoSpringApplication class
 *
 * @author haozhifeng
 * @date 2023/01/07
 */
@SpringBootApplication
public class HaoSpringApplication {
	public static void main(String[] args) {

		ConfigurableApplicationContext applicationContext = SpringApplication.run(HaoSpringApplication.class, args);


		System.out.println("--------------------");
		String[] beanDefinitionNames = applicationContext.getBeanDefinitionNames();
		for (String beanDefinitionName : beanDefinitionNames) {
			BeanDefinition beanDefinition = applicationContext.getBeanFactory().getBeanDefinition(beanDefinitionName);
			if ( null != beanDefinition.getBeanClassName()) {
				System.out.println(beanDefinition.getBeanClassName());
			} else {
				System.out.println(beanDefinition.getFactoryBeanName() + "##" + beanDefinition.getFactoryMethodName());
			}
		}
		System.out.println("--------------------");
	}
}
