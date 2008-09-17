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

package org.springframework.batch.core.step.tasklet;

import java.util.List;

import junit.framework.TestCase;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.JobSupport;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.MapExecutionContextDao;
import org.springframework.batch.core.repository.dao.MapJobExecutionDao;
import org.springframework.batch.core.repository.dao.MapJobInstanceDao;
import org.springframework.batch.core.repository.dao.MapStepExecutionDao;
import org.springframework.batch.core.repository.support.SimpleJobRepository;
import org.springframework.batch.core.step.StepExecutionSynchronizer;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.batch.repeat.support.RepeatTemplate;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

public class StepExecutorInterruptionTests extends TestCase {

	private TaskletStep step;

	private JobExecution jobExecution;

	private ItemWriter<Object> itemWriter;
	
	private StepExecution stepExecution;

	public void setUp() throws Exception {
		MapJobInstanceDao.clear();
		MapJobExecutionDao.clear();
		MapStepExecutionDao.clear();

		JobRepository jobRepository = new SimpleJobRepository(new MapJobInstanceDao(), new MapJobExecutionDao(),
				new MapStepExecutionDao(), new MapExecutionContextDao());

		JobSupport job = new JobSupport();
		step = new TaskletStep("interruptedStep");
		job.addStep(step);
		job.setBeanName("testJob");
		jobExecution = jobRepository.createJobExecution(job.getName(), new JobParameters());
		step.setJobRepository(jobRepository);
		step.setTransactionManager(new ResourcelessTransactionManager());
		itemWriter = new ItemWriter<Object>() {
			public void write(List<? extends Object> item) throws Exception {
			}
		};
		stepExecution = new StepExecution(step.getName(), jobExecution);
	}

	public void testInterruptStep() throws Exception {
	
		Thread processingThread = createThread(stepExecution);

		RepeatTemplate template = new RepeatTemplate();
		// N.B, If we don't set the completion policy it might run forever
		template.setCompletionPolicy(new SimpleCompletionPolicy(2));
		step.setTasklet(new TestingChunkOrientedTasklet<Object>(new ItemReader<Object>() {
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
		}, itemWriter, template));

		processingThread.start();
		Thread.sleep(100);
		processingThread.interrupt();

		int count = 0;
		while (processingThread.isAlive() && count < 1000) {
			Thread.sleep(20);
			count++;
		}

		assertTrue("Timed out waiting for step to be interrupted.", count < 1000);
		assertFalse(processingThread.isAlive());
		assertEquals(BatchStatus.STOPPED, stepExecution.getStatus());

	}

	public void testInterruptOnInterruptedException() throws Exception {

		Thread processingThread = createThread(stepExecution);

		step.setTasklet(new TestingChunkOrientedTasklet<Object>(new ItemReader<Object>() {
			public Object read() throws Exception {
				return null;
			}
		}, itemWriter));

		// This simulates the unlikely sounding, but in practice all too common
		// in Bamboo situation where the thread is interrupted before the lock
		// is taken.
		step.setSynchronizer(new StepExecutionSynchronizer() {
			public void lock(StepExecution stepExecution) throws InterruptedException {
				Thread.currentThread().interrupt();
				throw new InterruptedException();
			}

			public void release(StepExecution stepExecution) {
			}
		});

		processingThread.start();
		Thread.sleep(100);

		int count = 0;
		while (processingThread.isAlive() && count < 1000) {
			Thread.sleep(20);
			count++;
		}

		assertTrue("Timed out waiting for step to be interrupted.", count < 1000);
		assertFalse(processingThread.isAlive());
		assertEquals(BatchStatus.STOPPED, stepExecution.getStatus());

	}


	public void testLockNotReleasedIfChunkFails() throws Exception {

		step.setTasklet(new TestingChunkOrientedTasklet<Object>(new ItemReader<Object>() {
			public Object read() throws Exception {
				throw new RuntimeException("Planned!");
			}
		}, itemWriter));

		step.setSynchronizer(new StepExecutionSynchronizer() {
			private boolean locked = false;
			public void lock(StepExecution stepExecution) throws InterruptedException {
				locked = true;
			}
			public void release(StepExecution stepExecution) {
				assertTrue("Lock released before it is acquired", locked);
			}
		});
		
		step.execute(stepExecution);
		
		assertEquals("Planned!", stepExecution.getFailureExceptions().get(0).getMessage());
		assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
	}

	/**
	 * @return
	 */
	private Thread createThread(final StepExecution stepExecution) {
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
		return processingThread;
	}

}
