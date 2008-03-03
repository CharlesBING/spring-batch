/*
 * Copyright 2006-2007 the original author or authors.
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

package org.springframework.batch.execution.step.support;

import junit.framework.TestCase;

import org.springframework.batch.core.domain.BatchStatus;
import org.springframework.batch.core.domain.JobExecution;
import org.springframework.batch.core.domain.JobInterruptedException;
import org.springframework.batch.core.domain.JobParameters;
import org.springframework.batch.core.domain.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.execution.job.JobSupport;
import org.springframework.batch.execution.repository.SimpleJobRepository;
import org.springframework.batch.execution.repository.dao.JobExecutionDao;
import org.springframework.batch.execution.repository.dao.JobInstanceDao;
import org.springframework.batch.execution.repository.dao.MapJobExecutionDao;
import org.springframework.batch.execution.repository.dao.MapJobInstanceDao;
import org.springframework.batch.execution.repository.dao.MapStepExecutionDao;
import org.springframework.batch.execution.repository.dao.StepExecutionDao;
import org.springframework.batch.execution.step.ItemOrientedStep;
import org.springframework.batch.item.reader.AbstractItemReader;
import org.springframework.batch.item.reader.ItemReaderAdapter;
import org.springframework.batch.item.writer.AbstractItemWriter;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.batch.repeat.support.RepeatTemplate;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

public class StepExecutorInterruptionTests extends TestCase {

	private JobRepository jobRepository;

	private JobInstanceDao jobInstanceDao = new MapJobInstanceDao();

	private JobExecutionDao jobExecutionDao = new MapJobExecutionDao();

	private StepExecutionDao stepExecutionDao = new MapStepExecutionDao();

	private ItemOrientedStep step;

	private JobExecution jobExecution;

	private AbstractItemWriter itemWriter;

	public void setUp() throws Exception {
		MapJobInstanceDao.clear();
		MapJobExecutionDao.clear();
		MapStepExecutionDao.clear();

		jobRepository = new SimpleJobRepository(jobInstanceDao, jobExecutionDao, stepExecutionDao);

		JobSupport jobConfiguration = new JobSupport();
		step = new ItemOrientedStep("interruptedStep");
		jobConfiguration.addStep(step);
		jobConfiguration.setBeanName("testJob");
		jobExecution = jobRepository.createJobExecution(jobConfiguration, new JobParameters());
		step.setJobRepository(jobRepository);
		step.setTransactionManager(new ResourcelessTransactionManager());
		itemWriter = new AbstractItemWriter() {
			public void write(Object item) throws Exception {
			}
		};
		step.setItemProcessor(new SimpleItemProcessor(new ItemReaderAdapter(), itemWriter));
	}

	public void testInterruptChunk() throws Exception {

		final StepExecution stepExecution = new StepExecution(step, jobExecution);
		step.setItemProcessor(new SimpleItemProcessor(new AbstractItemReader() {
			public Object read() throws Exception {
				// do something non-trivial (and not Thread.sleep())
				double foo = 1;
				for (int i = 2; i < 250; i++) {
					foo = foo * i;
				}

				if (foo != 1) {
					return new Double(foo);
				}
				else {
					return null;
				}
			}
		}, itemWriter));

		Thread processingThread = new Thread() {
			public void run() {
				try {
					step.execute(stepExecution);
				}
				catch (JobInterruptedException e) {
					// do nothing...
				}
			}
		};

		processingThread.start();

		Thread.sleep(100);

		processingThread.interrupt();

		int count = 0;
		while (processingThread.isAlive() && count < 1000) {
			Thread.sleep(20);
			count++;
		}

		assertFalse(processingThread.isAlive());
		assertEquals(BatchStatus.STOPPED, stepExecution.getStatus());
	}

	public void testInterruptStep() throws Exception {
		RepeatTemplate template = new RepeatTemplate();
		// N.B, If we don't set the completion policy it might run forever
		template.setCompletionPolicy(new SimpleCompletionPolicy(2));
		step.setChunkOperations(template);
		testInterruptChunk();
	}

}
