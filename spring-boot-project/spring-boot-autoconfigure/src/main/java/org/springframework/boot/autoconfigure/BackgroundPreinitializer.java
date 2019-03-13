/*
 * Copyright 2012-2018 the original author or authors.
 *
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
 */

package org.springframework.boot.autoconfigure;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.validation.Configuration;
import javax.validation.Validation;

import org.apache.catalina.mbeans.MBeanFactory;

import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;

/**
 * {@link ApplicationListener} to trigger early initialization in a background thread of
 * time consuming tasks.
 *
 * 对于一些耗时的任务使用一个后台线程尽早触发它们开始执行初始化，这是Springboot的缺省行为。
 * 这些初始化动作也可以叫做预初始化。
 *
 * 设置系统属性spring.backgroundpreinitializer.ignore为true可以禁用该机制。
 * 该机制被禁用时，相应的初始化任务会发生在前台线程。
 *
 * <p>
 * Set the {@link #IGNORE_BACKGROUNDPREINITIALIZER_PROPERTY_NAME} system property to
 * {@code true} to disable this mechanism and let such initialization happen in the
 * foreground.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * @since 1.3.0
 */
@Order(LoggingApplicationListener.DEFAULT_ORDER + 1)
public class BackgroundPreinitializer
		implements ApplicationListener<SpringApplicationEvent> {

	/**
	 * System property that instructs Spring Boot how to run pre initialization. When the
	 * property is set to {@code true}, no pre-initialization happens and each item is
	 * initialized in the foreground as it needs to. When the property is {@code false}
	 * (default), pre initialization runs in a separate thread in the background.
	 * @since 2.1.0
	 */
	public static final String IGNORE_BACKGROUNDPREINITIALIZER_PROPERTY_NAME = "spring.backgroundpreinitializer.ignore";

	private static final AtomicBoolean preinitializationStarted = new AtomicBoolean(
			false);

	private static final CountDownLatch preinitializationComplete = new CountDownLatch(1);

	@Override
	public void onApplicationEvent(SpringApplicationEvent event) {
		if (!Boolean.getBoolean(IGNORE_BACKGROUNDPREINITIALIZER_PROPERTY_NAME)
				&& event instanceof ApplicationStartingEvent
				&& preinitializationStarted.compareAndSet(false, true)) {
			//如果没有设置spring.backgroundpreinitializer.ignore为true，
			//并且当前事件是ApplicationStartingEvent，
			// 并且preinitializationStarted表明预初始化任务尚未执行
			// 则 :
			// 1.先将preinitializationStarted设置为预初始化任务开始执行;
			// 2. 在1成功的情况下开始执行预初始化任务;
			performPreinitialization();
		}
		if ((event instanceof ApplicationReadyEvent
				|| event instanceof ApplicationFailedEvent)
				&& preinitializationStarted.get()) {
			try {
				// 如果到了应用就绪或者已经失败的时候，预初始化已经开始并且可能尚未结束,
				// 等到他结束，也就是要等到 preinitializationComplete 变成 0
				preinitializationComplete.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void performPreinitialization() {
		try {
			// 使用一个新的线程执行预初始化任务，使用 preinitializationComplete
			// 在前台主线程和该新背景线程之间协调任务的执行情况。
			Thread thread = new Thread(new Runnable() {

				@Override
				public void run() {
					runSafely(new ConversionServiceInitializer());
					runSafely(new ValidationInitializer());
					runSafely(new MessageConverterInitializer());
					runSafely(new MBeanFactoryInitializer());
					runSafely(new JacksonInitializer());
					runSafely(new CharsetInitializer());
					// 预初始化任务执行完成，将preinitializationComplete减为0。
					preinitializationComplete.countDown();
				}

				public void runSafely(Runnable runnable) {
					try {
						runnable.run();
					}
					catch (Throwable ex) {
						// Ignore
					}
				}

			}, "background-preinit");
			thread.start();
		}
		catch (Exception ex) {
			// This will fail on GAE where creating threads is prohibited. We can safely
			// continue but startup will be slightly slower as the initialization will now
			// happen on the main thread.
			// 针对GAE的处理，GAE上创建线程是被禁止的。
			// 这种情况下直接将preinitializationComplete减为0。
			//
			// 这种情况下我们可以安全地进行初始化，不会稍微慢一些因为预初始化任务会在前台主线程中完成。
			preinitializationComplete.countDown();
		}
	}

	/**
	 * Early initializer for Spring MessageConverters.
	 */
	private static class MessageConverterInitializer implements Runnable {

		@Override
		public void run() {
			new AllEncompassingFormHttpMessageConverter();
		}

	}

	/**
	 * Early initializer to load Tomcat MBean XML.
	 */
	private static class MBeanFactoryInitializer implements Runnable {

		@Override
		public void run() {
			new MBeanFactory();
		}

	}

	/**
	 * Early initializer for javax.validation.
	 */
	private static class ValidationInitializer implements Runnable {

		@Override
		public void run() {
			Configuration<?> configuration = Validation.byDefaultProvider().configure();
			configuration.buildValidatorFactory().getValidator();
		}

	}

	/**
	 * Early initializer for Jackson.
	 */
	private static class JacksonInitializer implements Runnable {

		@Override
		public void run() {
			Jackson2ObjectMapperBuilder.json().build();
		}

	}

	/**
	 * Early initializer for Spring's ConversionService.
	 */
	private static class ConversionServiceInitializer implements Runnable {

		@Override
		public void run() {
			new DefaultFormattingConversionService();
		}

	}

	private static class CharsetInitializer implements Runnable {

		@Override
		public void run() {
			StandardCharsets.UTF_8.name();
		}

	}

}
