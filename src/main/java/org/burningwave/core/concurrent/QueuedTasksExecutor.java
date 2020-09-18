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
package org.burningwave.core.concurrent;

import static org.burningwave.core.assembler.StaticComponentContainer.Methods;
import static org.burningwave.core.assembler.StaticComponentContainer.Objects;
import static org.burningwave.core.assembler.StaticComponentContainer.Synchronizer;
import static org.burningwave.core.assembler.StaticComponentContainer.Throwables;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.burningwave.core.Component;
import org.burningwave.core.ManagedLogger;
import org.burningwave.core.function.ThrowingRunnable;
import org.burningwave.core.function.ThrowingSupplier;

@SuppressWarnings({"unchecked", "resource"})
public class QueuedTasksExecutor implements Component {
	private final static Map<String, Task> runOnlyOnceTasksToBeExecuted;
	private final String instanceId;
	Thread executor;
	List<TaskAbst<?, ?>> tasksQueue;
	List<TaskAbst<?, ?>> asyncTasksInExecution;
	TaskAbst<?, ?> currentTask;
	Boolean supended;
	private int loggingThreshold;
	int defaultPriority;
	private long executedTasksCount;
	private boolean isDaemon;
	private String executorName;
	private String asyncExecutorName;
	private Boolean terminated;
	private Runnable initializer;
	boolean taskCreationTrackingEnabled;
	
	static {
		runOnlyOnceTasksToBeExecuted = new ConcurrentHashMap<>();
	}
	
	QueuedTasksExecutor(String executorName, String asyncExecutorName, int defaultPriority, boolean isDaemon, int loggingThreshold) {
		tasksQueue = new CopyOnWriteArrayList<>();
		asyncTasksInExecution = new CopyOnWriteArrayList<>();
		instanceId = Objects.getCurrentId(this);
		this.loggingThreshold = loggingThreshold;
		initializer = () -> {
			this.executorName = executorName;
			this.asyncExecutorName = asyncExecutorName;
			this.defaultPriority = defaultPriority;
			this.isDaemon = isDaemon;
			init0();
		};		
		init();
	}
	
	void init() {
		initializer.run();
	}
	
	Object getMutex(String name) {
		return Synchronizer.getMutex(instanceId + "_" + name);
	}
	
	void init0() {		
		supended = Boolean.FALSE;
		terminated = Boolean.FALSE;
		executedTasksCount = 0;
		executor = new Thread(() -> {
			while (!terminated) {
				if (!tasksQueue.isEmpty()) {
					Iterator<TaskAbst<?, ?>> taskIterator = tasksQueue.iterator();
					while (taskIterator.hasNext()) {
						synchronized(getMutex("resumeCaller")) {
							try {
								if (supended) {
									logInfo("... suspendig");
									getMutex("resumeCaller").wait();
									logInfo("... resuming", this.executor);
									break;
								}
							} catch (InterruptedException exc) {
								logWarn("Exception occurred", exc);
							}
						}
						TaskAbst<?, ?> task = this.currentTask = taskIterator.next();
						synchronized (task) {
							if (!tasksQueue.remove(task)) {
								this.currentTask = null;
								continue;
							} else if (TaskAbst.Execution.Mode.ASYNC.equals(task.executionMode)) {
								asyncTasksInExecution.add(task);
							}
						}
						Thread executor = task.executor;
						int currentExecutablePriority = task.getPriority();
						if (executor.getPriority() != currentExecutablePriority) {
							executor.setPriority(currentExecutablePriority);
						}
						boolean isSync = executor == this.executor;
						if (isSync) {
							task.execute();
						} else if (task.executionMode == TaskAbst.Execution.Mode.ASYNC) {
							executor.start();
						}
						if (isSync && executor.getPriority() != this.defaultPriority) {
							executor.setPriority(this.defaultPriority);
						}
						if (isSync) {
							incrementAndlogExecutedTaskCounter();
						}						
						synchronized(getMutex("suspensionCaller")) {
							getMutex("suspensionCaller").notifyAll();
						}
						if (terminated) {
							break;
						}
					}
				} else {
					synchronized(getMutex("executingFinishedWaiter")) {
						getMutex("executingFinishedWaiter").notifyAll();
					}
					synchronized(getMutex("executableCollectionFiller")) {
						if (tasksQueue.isEmpty()) {
							try {
								getMutex("executableCollectionFiller").wait();
							} catch (InterruptedException exc) {
								logWarn("Exception occurred", exc);
							}
						}
					}
				}
			}
		}, executorName);
		executor.setPriority(this.defaultPriority);
		executor.setDaemon(isDaemon);
		executor.start();
	}

	void incrementAndlogExecutedTaskCounter() {
		long counter = ++this.executedTasksCount;
		if (counter % loggingThreshold == 0) {
			logInfo("Executed {} tasks", counter);
		}
	}
	
	public static QueuedTasksExecutor create(String executorName, String asyncExecutorName, int initialPriority) {
		return create(executorName, asyncExecutorName, initialPriority, false, 100, false);
	}
	
	public static QueuedTasksExecutor create(String executorName, String asyncExecutorName, int initialPriority, boolean daemon, int loggingThreshold, boolean undestroyable) {
		if (undestroyable) {
			String creatorClass = Thread.currentThread().getStackTrace()[2].getClassName();
			return new QueuedTasksExecutor(executorName, asyncExecutorName, initialPriority, daemon, loggingThreshold) {
				
				@Override
				public boolean shutDown(boolean waitForTasksTermination) {
					if (Thread.currentThread().getStackTrace()[4].getClassName().equals(creatorClass)) {
						return super.shutDown(waitForTasksTermination);
					}
					return false;
				}
				
			};
		} else {
			return new QueuedTasksExecutor(executorName, asyncExecutorName, initialPriority, daemon, loggingThreshold);
		}
	}
	
	public QueuedTasksExecutor setTasksCreationTrackingFlag(boolean flag) {
		this.taskCreationTrackingEnabled = flag;
		return this;
	}
	
	public <T> ProducerTask<T> createTask(ThrowingSupplier<T, ? extends Throwable> executable) {
		ProducerTask<T> task = (ProducerTask<T>) getProducerTaskSupplier().apply((ThrowingSupplier<Object, ? extends Throwable>) executable);
		task.priority = this.defaultPriority;
		return task;
	}
	
	<T> Function<ThrowingSupplier<T, ? extends Throwable>, ProducerTask<T>> getProducerTaskSupplier() {
		return executable -> new ProducerTask<T>(executable, taskCreationTrackingEnabled) {
			@Override
			ProducerTask<T> addToQueue() {
				return QueuedTasksExecutor.this.addToQueue(this, false);
			};
		};
	}
	
	public Task createTask(ThrowingRunnable<? extends Throwable> executable) {
		Task task = getTaskSupplier().apply((ThrowingRunnable<? extends Throwable>) executable);
		task.priority = this.defaultPriority;
		return task;
	}
	
	<T> Function<ThrowingRunnable<? extends Throwable> , Task> getTaskSupplier() {
		return executable -> new Task(executable, taskCreationTrackingEnabled) {
			@Override
			Task addToQueue() {
				return QueuedTasksExecutor.this.addToQueue(this, false);
			};
		};
	}

	<E, T extends TaskAbst<E, T>> T addToQueue(T task, boolean skipCheck) {
		if (skipCheck || canBeExecuted(task)) {
			try {
				setExecutorOf(task);
				if (TaskAbst.Execution.Mode.PURE_ASYNC.equals(task.executionMode)) {
					asyncTasksInExecution.add(task);
					task.executor.start();
				} else {
					tasksQueue.add(task);
					synchronized(getMutex("executableCollectionFiller")) {
						getMutex("executableCollectionFiller").notifyAll();
					}
				}
			} catch (Throwable exc) {
				logWarn("Exception occurred", exc);
			}
		} 
		return task;
	}

	private <E, T extends TaskAbst<E, T>> void setExecutorOf(T task) {
		if (TaskAbst.Execution.Mode.SYNC.equals(task.executionMode)) {
			task.setExecutor(this.executor);
		} else if (TaskAbst.Execution.Mode.ASYNC.equals(task.executionMode) || 
			TaskAbst.Execution.Mode.PURE_ASYNC.equals(task.executionMode)) {
			Thread executor = new Thread(() -> {
				synchronized(task) {
					task.execute();
					asyncTasksInExecution.remove(task);
					incrementAndlogExecutedTaskCounter();
				}
			}, asyncExecutorName);
			executor.setPriority(task.priority);
			task.setExecutor(executor);
		}		
	}

	<E, T extends TaskAbst<E, T>> boolean canBeExecuted(T task) {
		if (task instanceof Task && ((Task)task).runOnlyOnce) {
			return !((Task)task).hasBeenExecutedChecker.get() && runOnlyOnceTasksToBeExecuted.putIfAbsent(((Task)task).id, (Task)task) == null;
		}
		return !task.hasFinished();
	}
	
	public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task) {
		return waitFor(task, Thread.currentThread().getPriority());
	}
	
	public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task, int priority) {
		changePriorityToAllTaskBefore(task, priority);
		task.waitForFinish(false);
		return this;
	}
	
	public QueuedTasksExecutor waitForTasksEnding() {
		return waitForTasksEnding(Thread.currentThread().getPriority(), false);
	}
	
	public QueuedTasksExecutor waitForTasksEnding(int priority, boolean waitForNewAddedTasks) {
		executor.setPriority(priority);
		tasksQueue.stream().forEach(executable -> executable.changePriority(priority)); 
		while (!tasksQueue.isEmpty()) {
			synchronized(getMutex("executingFinishedWaiter")) {
				if (!tasksQueue.isEmpty()) {
					try {
						getMutex("executingFinishedWaiter").wait();
					} catch (InterruptedException exc) {
						logWarn("Exception occurred", exc);
					}
				}
			}
		}
		waitForAsyncTasksEnding(priority);
		executor.setPriority(this.defaultPriority);
		if (waitForNewAddedTasks && (!tasksQueue.isEmpty() || !asyncTasksInExecution.isEmpty())) {
			waitForTasksEnding(priority, waitForNewAddedTasks);
		}
		return this;
	}
	
	public QueuedTasksExecutor changePriority(int priority) {
		this.defaultPriority = priority;
		executor.setPriority(priority);
		tasksQueue.stream().forEach(executable -> executable.changePriority(priority));
		return this;
	}
	
	public QueuedTasksExecutor suspend() {
		return suspend(true);
	}
	
	public QueuedTasksExecutor suspend(boolean immediately) {
		return suspend0(immediately, Thread.currentThread().getPriority());
	}
	
	public QueuedTasksExecutor suspend(boolean immediately, int priority) {
		return suspend0(immediately, priority);
	}
	
	QueuedTasksExecutor suspend0(boolean immediately, int priority) {
		int counter = 0;
		logInfo("suspend0 {}", ++counter);
		executor.setPriority(priority);
		if (immediately) {
			supended = Boolean.TRUE;
			logInfo("suspend0 {}", ++counter);
			waitForAsyncTasksEnding(priority);
			logInfo("suspend0 {}", ++counter);
			TaskAbst<?, ?> currentTask = this.currentTask;
			if (currentTask != null && !currentTask.hasFinished()) {
				logInfo("suspend0 {}", ++counter);
				synchronized (getMutex("suspensionCaller")) {
					logInfo("suspend0 {}", ++counter);
					currentTask = this.currentTask;
					if (currentTask != null && !currentTask.hasFinished()) {
						try {
							logInfo("suspend0 {}", ++counter);
							getMutex("suspensionCaller").wait();
							logInfo("suspend0 {}", ++counter);
						} catch (InterruptedException exc) {
							logWarn("Exception occurred", exc);
						}
					}
				}
			}
		} else {
			waitForAsyncTasksEnding(priority);
			Task supendingTask = createSuspendingTask(priority);
			changePriorityToAllTaskBefore(supendingTask.submit(), priority);
			supendingTask.waitForFinish(false);
		}
		executor.setPriority(this.defaultPriority);
		logInfo("suspend0 exit");
		return this;
	}

	Task createSuspendingTask(int priority) {
		return createTask((ThrowingRunnable<?>)() -> supended = Boolean.TRUE).runOnlyOnce(getOperationId("suspend"), () -> supended).changePriority(priority);
	}

	void waitForAsyncTasksEnding(int priority) {
		asyncTasksInExecution.stream().forEach(asyncTask -> {
			Thread taskExecutor = asyncTask.executor;
			if (taskExecutor != null) {
				taskExecutor.setPriority(priority);
			}
			asyncTask.join0(false);
		});
	}

	<E, T extends TaskAbst<E, T>> void changePriorityToAllTaskBefore(T task, int priority) {
		int taskIndex = tasksQueue.indexOf(task);
		if (taskIndex != -1) {
			Iterator<TaskAbst<?, ?>> taskIterator = tasksQueue.iterator();
			int idx = 0;
			while (taskIterator.hasNext()) {
				TaskAbst<?, ?> currentIterated = taskIterator.next();
				if (idx < taskIndex) {					
					if (currentIterated != task) {
						task.changePriority(priority);
					} else {
						break;
					}
				}
				idx++;
			}
		}
		waitForAsyncTasksEnding(priority);
	}

	public QueuedTasksExecutor resume() {
		synchronized(getMutex("resumeCaller")) {
			try {
				supended = Boolean.FALSE;
				getMutex("resumeCaller").notifyAll();
			} catch (Throwable exc) {
				logWarn("Exception occurred", exc);
			}
		}	
		return this;
	}
	
	public boolean isSuspended() {
		return supended;
	}
	
	public boolean shutDown(boolean waitForTasksTermination) {
		Collection<TaskAbst<?, ?>> executables = this.tasksQueue;
		Thread executor = this.executor;
		int counter = 0;
		if (waitForTasksTermination) {
			suspend(false);
		} else {
			logInfo("shutDown {}", ++counter);
			suspend();
		}
		this.terminated = Boolean.TRUE;
		logQueueInfo();
		executables.clear();
		asyncTasksInExecution.clear();
		logInfo("shutDown {}", ++counter);
		resume();
		logInfo("shutDown {}", ++counter);
		try {
			logInfo("shutDown {}", ++counter);
			synchronized(getMutex("executableCollectionFiller")) {
				getMutex("executableCollectionFiller").notifyAll();
			}
			logInfo("shutDown {}", ++counter);
		} catch (Throwable exc) {
			logWarn("Exception occurred", exc);
		}	
		try {
			logInfo("shutDown {}", ++counter);
			logInfo("thread state: {}", executor.getState());
			executor.join();
			logInfo("shutDown {}", ++counter);
			closeResources();
			logInfo("shutDown {}", ++counter);
		} catch (InterruptedException exc) {
			logError("Exception occurred", exc);
		}
		return true;
	}
	
	public void logQueueInfo() {
		List<TaskAbst<?, ?>> tasks = new ArrayList<>(tasksQueue);
		tasks.addAll(this.asyncTasksInExecution);
		logQueueInfo(this.executedTasksCount, tasks);
	}
	
	private void logQueueInfo(Long executedTasksCount, Collection<TaskAbst<?, ?>> executables) {
		Collection<String> executablesLog = executables.stream().map(task -> "\t" + task.executable.toString()).collect(Collectors.toList());
		StringBuffer log = new StringBuffer(this.executor + " - Executed tasks: ")
			.append(executedTasksCount).append(", Unexecuted tasks: ")
			.append(executablesLog.size());
			
		if (executablesLog.size() > 0) {
			log.append(":\n\t")
			.append(String.join("\n\t", executablesLog));
		}		
		logInfo(log.toString());
	}
	
	@Override
	public void close() {
		shutDown(true);
	}
	
	void closeResources() {
		try {
			executor.interrupt();
		} catch (Throwable e) {
			logWarn("Exception occurred while interrupting thread {} of {}", executor, this);
		}
		executor = null;
		tasksQueue = null;
		asyncTasksInExecution = null;
		currentTask = null;
		initializer = null;
		terminated = null;
		supended = null;
		logInfo("All resources of '{}' have been closed", executorName);
		executorName = null;		
	}
	
	public static abstract class TaskAbst<E, T extends TaskAbst<E, T>> implements ManagedLogger {
		
		static class Execution {
			public static enum Mode {
				SYNC, ASYNC, PURE_ASYNC
			}
		}
		StackTraceElement[] stackTraceOnCreation;
		List<StackTraceElement> creatorInfos;
		boolean started;
		boolean submited;
		E executable;
		Execution.Mode executionMode;
		int priority;
		Thread executor;
		Throwable exc;
		
		public TaskAbst(E executable, boolean creationTracking) {
			this.executable = executable;
			this.executionMode = Execution.Mode.SYNC;
			if (creationTracking) {
				stackTraceOnCreation = Thread.currentThread().getStackTrace();
			}
		}
		
		public List<StackTraceElement> getCreatorInfos() {
			if (this.creatorInfos == null) {
				if (stackTraceOnCreation != null) {
					this.creatorInfos = Collections.unmodifiableList(
						Methods.retrieveCallersInfo(
							this.stackTraceOnCreation,
							(clientMethodSTE, currentIteratedSTE) -> !currentIteratedSTE.getClassName().startsWith(QueuedTasksExecutor.class.getName()),
							-1
						)
					);
				} else {
					logWarn("Tasks creation tracking was disabled when {} was created", this);
				}
			}
			return creatorInfos;
		}
		
		public boolean hasFinished() {
			return executable == null;
		}
		
		void join0(boolean ignoreThreadCheck) {
			if (!hasFinished() && ((ignoreThreadCheck) ||
				(!ignoreThreadCheck && Thread.currentThread() != executor && executor != null))
			) {	
				synchronized (this) {
					if (!hasFinished() && ((ignoreThreadCheck) ||
						(!ignoreThreadCheck && Thread.currentThread() != executor && executor != null))) {
						try {
							wait();
							join0(ignoreThreadCheck);
						} catch (InterruptedException exc) {
							throw Throwables.toRuntimeException(exc);
						}
					}
				}
			}
		}		
		
		public T async() {
			this.executionMode = Execution.Mode.ASYNC;
			return (T)this;
		}
		
		public T pureAsync() {
			this.executionMode = Execution.Mode.PURE_ASYNC;
			return (T)this;
		}
		
		public T sync() {
			this.executionMode = Execution.Mode.SYNC;
			return (T)this;
		}
		
		void execute() {
			started = true;
			synchronized (this) {
				notifyAll();
			}
			try {
				execute0();					
			} catch (Throwable exc) {
				this.exc = exc;
				logError("Exception occurred while executing " + this, exc);
			}
			executable = null;
			executor = null;
			synchronized(this) {
				notifyAll();
			}
		}
		
		public T waitForFinish() {
			return waitForFinish(false);
		}
		
		public abstract T waitForFinish(boolean ignoreThreadCheck);
		
		public T waitForStarting() {
			if (!started) {
				synchronized (this) {
					if (!started) {
						try {
							wait();
							waitForStarting();
						} catch (InterruptedException exc) {
							throw Throwables.toRuntimeException(exc);
						}
					}
				}
			}
			return (T)this;
		}
		
		public boolean hasStarted() {
			return started;
		}
		
		abstract void execute0() throws Throwable;
		
		T setExecutor(Thread executor) {
			this.executor = executor;
			return (T)this;
		}
		
		public T changePriority(int priority) {
			this.priority = priority;
			return (T)this;
		}
		
		public T setPriorityToCurrentThreadPriority() {
			return changePriority(Thread.currentThread().getPriority());
		}
		
		public int getPriority() {
			return priority;
		}
		
		public Throwable getException() {
			return exc;
		}
		
		public boolean endedWithErrors() {
			return exc != null;
		}
		
		public final T submit() {
			if (!submited) {
				synchronized(this) {
					if (!submited) {
						submited = true;
					} else {
						throw Throwables.toRuntimeException("Could not submit task " + this + " twice");
					}
				}
			} else {
				throw Throwables.toRuntimeException("Could not submit task " + this + " twice");
			}
			return addToQueue();
		}
		
		public boolean isSubmited() {
			return submited;
		}
		
		abstract T addToQueue();
		
	}
	
	public static abstract class Task extends TaskAbst<ThrowingRunnable<? extends Throwable>, Task> {
		Supplier<Boolean> hasBeenExecutedChecker;
		boolean runOnlyOnce;
		public String id;
		
		Task(ThrowingRunnable<? extends Throwable> executable, boolean creationTracking) {
			super(executable, creationTracking);
		}

		@Override
		void execute0() throws Throwable {
			try {
				this.executable.run();
			} finally {
				if (runOnlyOnce) {
					runOnlyOnceTasksToBeExecuted.remove(((Task)this).id);
				}
			}
		}
		
		@Override
		public Task waitForFinish(boolean ignoreThread) {
			if (!runOnlyOnce) {
				join0(ignoreThread);
			} else {
				Task task = getEffectiveTask();
				if (task != null) {
					if (task == this) {
						join0(ignoreThread);
					} else {
						task.join0(ignoreThread);
					}
				}
			}
			return this;
		}

		Task getEffectiveTask() {
			Task task = QueuedTasksExecutor.runOnlyOnceTasksToBeExecuted.get(id);
			return task;
		}
		
		@Override
		public boolean hasFinished() {
			if (!runOnlyOnce) {
				return super.hasFinished();
			} else {
				Task task = getEffectiveTask();
				if (task != null) {
					if (task == this) {
						return super.hasFinished();
					} else {
						return task.hasFinished();
					}
				}
				executable = null;
				return hasBeenExecutedChecker.get();
			}
		}
		
		public Task runOnlyOnce(String id, Supplier<Boolean> hasBeenExecutedChecker) {
			runOnlyOnce = true;
			this.id = id;
			this.hasBeenExecutedChecker = hasBeenExecutedChecker;
			return this;
		}
		
	}
	
	public static abstract class ProducerTask<T> extends TaskAbst<ThrowingSupplier<T, ? extends Throwable>, ProducerTask<T>> {
		private T result;
		
		ProducerTask(ThrowingSupplier<T, ? extends Throwable> executable, boolean creationTracking) {
			super(executable, creationTracking);
		}		
		
		@Override
		void execute0() throws Throwable {
			result = executable.get();			
		}
		
		public T join() {
			return join(false);
		}
		
		public T join(boolean ignoreThread) {
			join0(ignoreThread);
			return result;
		}
		
		public T get() {
			return result;
		}
		
		@Override
		public ProducerTask<T> waitForFinish(boolean ignoreThreadCheck) {
			join0(ignoreThreadCheck);
			return this;
		}
	}
	
	public static class Group {
		Map<String, QueuedTasksExecutor> queuedTasksExecutors;
		
		Group(String name, boolean isDaemon) {
			queuedTasksExecutors = new HashMap<>();
			queuedTasksExecutors.put(
				String.valueOf(Thread.MAX_PRIORITY),
				createQueuedTasksExecutor(
					name + " - High priority tasks executor",
					name + " - High priority async tasks executor",
					Thread.MAX_PRIORITY, isDaemon, 10
				)
			);
			queuedTasksExecutors.put(
				String.valueOf(Thread.NORM_PRIORITY),
				createQueuedTasksExecutor(
					name + " - Normal priority tasks executor", 
					name + " - Normal priority async tasks executor",
					Thread.NORM_PRIORITY, isDaemon, 100
				)
			);
			queuedTasksExecutors.put(
				String.valueOf(Thread.MIN_PRIORITY),
				createQueuedTasksExecutor(
					name + " - Low priority tasks executor",
					name + " - Low priority async tasks executor", 
					Thread.MIN_PRIORITY, isDaemon, 1000
				)
			);
		}
		
		public static Group create(String name, boolean isDaemon) {
			return create(name, isDaemon, false);
		}
		
		public static Group create(String name, boolean isDaemon, boolean undestroyableFromExternal) {
			if (!undestroyableFromExternal) {
				return new Group(name, isDaemon);
			} else {
				String creatorClass = Thread.currentThread().getStackTrace()[2].getClassName();
				return new Group(name, isDaemon) {
					@Override
					public boolean shutDown(boolean waitForTasksTermination) {
						if (Thread.currentThread().getStackTrace()[2].getClassName().equals(creatorClass)) {	
							return super.shutDown(waitForTasksTermination);
						}
						return false;
					}
				};
			}
		}
		
		public <T> ProducerTask<T> createTask(ThrowingSupplier<T, ? extends Throwable> executable) {
			return createTask(executable, Thread.currentThread().getPriority());
		}
		
		public <T> ProducerTask<T> createTask(ThrowingSupplier<T, ? extends Throwable> executable, int priority) {
			return getByPriority(priority).createTask(executable);
		}

		QueuedTasksExecutor getByPriority(int priority) {
			QueuedTasksExecutor queuedTasksExecutor = queuedTasksExecutors.get(String.valueOf(priority));
			if (queuedTasksExecutor == null) {
				queuedTasksExecutor = queuedTasksExecutors.get(String.valueOf(checkAndCorrectPriority(priority)));
			}	
			return queuedTasksExecutor;
		}

		int checkAndCorrectPriority(int priority) {
			if (priority != Thread.MIN_PRIORITY || 
				priority != Thread.NORM_PRIORITY || 
				priority != Thread.MAX_PRIORITY	
			) {
				if (priority < Thread.NORM_PRIORITY) {
					return Thread.MIN_PRIORITY;
				} else if (priority < Thread.MAX_PRIORITY) {
					return Thread.NORM_PRIORITY;
				} else {
					return Thread.MAX_PRIORITY;
				}
			}
			return priority;
		}
		
		public Task createTask(ThrowingRunnable<? extends Throwable> executable) {
			return createTask(executable, Thread.currentThread().getPriority());
		}
		
		public Task createTask(ThrowingRunnable<? extends Throwable> executable, int priority) {
			return getByPriority(priority).createTask(executable);
		}
		
		QueuedTasksExecutor createQueuedTasksExecutor(String executorName, String asyncExecutorName, int priority, boolean isDaemon, int loggingThreshold) {
			return new QueuedTasksExecutor(executorName, asyncExecutorName, priority, isDaemon, loggingThreshold) {
				
				<T> Function<ThrowingSupplier<T, ? extends Throwable>, QueuedTasksExecutor.ProducerTask<T>> getProducerTaskSupplier() {
					return executable -> new QueuedTasksExecutor.ProducerTask<T>(executable, taskCreationTrackingEnabled) {
						
						@Override
						QueuedTasksExecutor.ProducerTask<T> addToQueue() {
							return Group.this.getByPriority(this.priority).addToQueue(this, false);
						};
						
						@Override
						public QueuedTasksExecutor.ProducerTask<T> changePriority(int priority) {
							Group.this.changePriority(this, priority);
							return this;
						};
						
						@Override
						public QueuedTasksExecutor.ProducerTask<T> async() {
							Group.this.changeExecutionMode(this, QueuedTasksExecutor.TaskAbst.Execution.Mode.ASYNC);
							return this;
						}
						
						@Override
						public QueuedTasksExecutor.ProducerTask<T> pureAsync() {
							Group.this.changeExecutionMode(this, QueuedTasksExecutor.TaskAbst.Execution.Mode.PURE_ASYNC);
							return this;
						}
						
						@Override
						public QueuedTasksExecutor.ProducerTask<T> sync() {
							Group.this.changeExecutionMode(this, QueuedTasksExecutor.TaskAbst.Execution.Mode.SYNC);
							return this;
						}
						
					};
				}
				
				<T> Function<ThrowingRunnable<? extends Throwable> , QueuedTasksExecutor.Task> getTaskSupplier() {
					return executable -> new QueuedTasksExecutor.Task(executable, taskCreationTrackingEnabled) {
						
						@Override
						QueuedTasksExecutor.Task addToQueue() {
							return Group.this.getByPriority(this.priority).addToQueue(this, false);
						};
						
						@Override
						public QueuedTasksExecutor.Task changePriority(int priority) {
							if (runOnlyOnce) {
								Task task = getEffectiveTask();
								if (task != null && task != this) {
									task.changePriority(priority);
									return this;
								}
							}
							Group.this.changePriority(this, priority);
							return this;
						};
						
						@Override
						public QueuedTasksExecutor.Task async() {
							return setExecutionMode(this::async, QueuedTasksExecutor.TaskAbst.Execution.Mode.PURE_ASYNC);
						}
						
						@Override
						public QueuedTasksExecutor.Task pureAsync() {
							return setExecutionMode(this::pureAsync, QueuedTasksExecutor.TaskAbst.Execution.Mode.PURE_ASYNC);
						}
						
						public QueuedTasksExecutor.Task sync() {
							return setExecutionMode(this::sync, QueuedTasksExecutor.TaskAbst.Execution.Mode.SYNC);
						}
						
						private QueuedTasksExecutor.Task setExecutionMode(
							Runnable executionModeSetter,
							QueuedTasksExecutor.TaskAbst.Execution.Mode newValue
						) {
							if (runOnlyOnce) {
								Task task = getEffectiveTask();
								if (task != null && task != this) {
									executionModeSetter.run();
									return this;
								}
							}
							Group.this.changeExecutionMode(this, newValue);
							return this;
						}
					};
				}
				
				@Override
				public QueuedTasksExecutor waitForTasksEnding(int priority, boolean waitForNewAddedTasks) {
					if (priority == defaultPriority) {
						while (!tasksQueue.isEmpty()) {
							synchronized(getMutex("executingFinishedWaiter")) {
								if (!tasksQueue.isEmpty()) {
									try {
										getMutex("executingFinishedWaiter").wait();
									} catch (InterruptedException exc) {
										logWarn("Exception occurred", exc);
									}
								}
							}
						}
						asyncTasksInExecution.stream().forEach(task -> {
							task.join0(false);
						});
					} else {	
						tasksQueue.stream().forEach(executable -> executable.changePriority(priority)); 
						waitForAsyncTasksEnding(priority);				
					}
					if (waitForNewAddedTasks && (!asyncTasksInExecution.isEmpty() || !tasksQueue.isEmpty())) {
						waitForTasksEnding(priority, waitForNewAddedTasks);
					}
					return this;
				}
				
				@Override
				public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task, int priority) {
					task.waitForFinish(false);
					return this;
				}
				
				@Override
				Task createSuspendingTask(int priority) {
					return createTask((ThrowingRunnable<?>)() -> supended = Boolean.TRUE);
				}
			};
		}
		
		<E, T extends TaskAbst<E, T>> Group changePriority(T task, int priority) {
			int oldPriority = task.priority;
			task.priority = checkAndCorrectPriority(priority);
			if (oldPriority != priority) {
				synchronized (task) {
					if (getByPriority(oldPriority).tasksQueue.remove(task)) {
						getByPriority(priority).addToQueue(task, true);
					}
				}
			}
			return this;
		}
		
		<E, T extends TaskAbst<E, T>> Group changeExecutionMode(T task, QueuedTasksExecutor.TaskAbst.Execution.Mode executionMode) {
			if (task.executionMode != executionMode) {
				task.executionMode = executionMode;
				synchronized (task) {
					QueuedTasksExecutor queuedTasksExecutor = getByPriority(task.priority);
					if (queuedTasksExecutor.tasksQueue.contains(task)) {
						queuedTasksExecutor.setExecutorOf(task);
					}
				}
			}
			return this;
		}
		
		public boolean shutDown(boolean waitForTasksTermination) {
			for (Entry<String, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				queuedTasksExecutorBox.getValue().shutDown(waitForTasksTermination);
			}
			queuedTasksExecutors.clear();
			queuedTasksExecutors = null;
			return true;
		}
		
		public Group waitForTasksEnding() {
			return waitForTasksEnding(Thread.currentThread().getPriority(), false);
		}
		
		public Group waitForTasksEnding(boolean waitForNewAddedTasks) {
			return waitForTasksEnding(Thread.currentThread().getPriority(), waitForNewAddedTasks);
		}
		
		public Group waitForTasksEnding(int priority, boolean waitForNewAddedTasks) {
			QueuedTasksExecutor lastToBeWaitedFor = getByPriority(priority);
			for (Entry<String, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				QueuedTasksExecutor queuedTasksExecutor = queuedTasksExecutorBox.getValue();
				if (queuedTasksExecutor != lastToBeWaitedFor) {
					queuedTasksExecutor.waitForTasksEnding(priority, waitForNewAddedTasks);
				}
			}
			lastToBeWaitedFor.waitForTasksEnding(priority, waitForNewAddedTasks);	
			for (Entry<String, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				QueuedTasksExecutor queuedTasksExecutor = queuedTasksExecutorBox.getValue();
				if (waitForNewAddedTasks && (!queuedTasksExecutor.tasksQueue.isEmpty() || !queuedTasksExecutor.asyncTasksInExecution.isEmpty())) {
					waitForTasksEnding(priority, waitForNewAddedTasks);
					break;
				}
			}
			return this;
		}

		public <E, T extends TaskAbst<E, T>> Group waitFor(T task) {
			return waitFor(task, Thread.currentThread().getPriority());	
		}
		
		public <E, T extends TaskAbst<E, T>> Group waitFor(T task, int priority) {
			if (task.getPriority() != priority) {
				task.changePriority(priority);
			}
			task.waitForFinish(false);
			return this;
		}
		
		public Group setTasksCreationTrackingFlag(boolean flag) {
			for (Entry<String, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				queuedTasksExecutorBox.getValue().setTasksCreationTrackingFlag(flag);
			}
			return this;
		}
		
		public Group logQueuesInfo() {
			for (Entry<String, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				queuedTasksExecutorBox.getValue().logQueueInfo();
			}
			return this;
		}
	}
}
