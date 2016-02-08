package com.logsniffer.source.composition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logsniffer.event.Event;
import com.logsniffer.fields.FieldBaseTypes;
import com.logsniffer.model.Log;
import com.logsniffer.model.LogEntry;
import com.logsniffer.model.LogInputStream;
import com.logsniffer.model.LogPointer;
import com.logsniffer.model.LogPointerFactory;
import com.logsniffer.model.LogRawAccess;
import com.logsniffer.model.SeverityLevel;
import com.logsniffer.reader.FormatException;
import com.logsniffer.reader.LogEntryReader;
import com.logsniffer.source.composition.ComposedLogPointer.PointerPart;

public class CompositionReader implements LogEntryReader<ComposedLogInputStream> {
	private static final Logger logger = LoggerFactory.getLogger(CompositionReader.class);
	private final List<LogInstance> composedLogs;

	public CompositionReader(final List<LogInstance> composedLogs) {
		super();
		this.composedLogs = composedLogs;
	}

	@Override
	public LinkedHashMap<String, FieldBaseTypes> getFieldTypes() throws FormatException {
		final LinkedHashMap<String, FieldBaseTypes> composedFields = new LinkedHashMap<>();
		for (final LogInstance i : composedLogs) {
			composedFields.putAll(i.getReader().getFieldTypes());
		}
		return composedFields;
	}

	private final class LogInstanceEntry implements Comparable<LogInstanceEntry> {
		private final int instanceIndex;
		private final LogEntry entry;
		private final long logSourceId;
		private final String logPath;
		private long tmst;

		public LogInstanceEntry(final int instanceIndex, final LogEntry entry, final long logSourceId,
				final String logPath) {
			super();
			this.instanceIndex = instanceIndex;
			this.entry = entry;
			this.logSourceId = logSourceId;
			this.logPath = logPath;
			final Date tmstD = entry.getTimeStamp();
			if (tmstD != null) {
				tmst = tmstD.getTime();
			} else {
				tmst = 0;
			}
		}

		@Override
		public int compareTo(final LogInstanceEntry o) {
			if (tmst == o.tmst) {
				return instanceIndex - o.instanceIndex;
			} else {
				return (int) (tmst - o.tmst);
			}

		}
	}

	private abstract class CompositionReaderExecutor {
		private static final int BUFFER_SIZE_PER_THREAD = 10;
		private final Semaphore processingSemaphore = new Semaphore(1);
		private Semaphore threadSemaphore;
		private boolean running;
		private boolean blockedBuffer = true;
		private Exception terminationException;
		private final TreeSet<LogInstanceEntry> instanceEntries = new TreeSet<>();
		private final List<SubReaderThread> activeThreads = new ArrayList<>();
		private Semaphore[] instanceEntryBuffer;
		private AtomicInteger[] instanceCounters;
		private final PointerPart[] lastOffsets;

		public CompositionReaderExecutor(final PointerPart[] lastOffsets) {
			super();
			this.lastOffsets = lastOffsets;
		}

		protected boolean process(final LogInstanceEntry instanceEntry, final SubReaderThread subReaderThread) {
			try {
				if (instanceEntry != null) {
					if (blockedBuffer) {
						instanceEntryBuffer[instanceEntry.instanceIndex].acquire();
					}
					synchronized (instanceEntries) {
						instanceEntries.add(instanceEntry);
					}
					instanceCounters[instanceEntry.instanceIndex].incrementAndGet();
				}
				if (processingSemaphore.tryAcquire()) {
					try {
						synchronized (activeThreads) {
							while (!instanceEntries.isEmpty() && running) {
								// Check first all thread have filled the
								// buffers
								for (final SubReaderThread t : activeThreads) {
									if (instanceCounters[t.logInstanceIndex].get() == 0) {
										return running;
									}
								}
								// Gather the next ordered entry, rewrite the
								// pointers
								// and
								// delegate to the consumer
								LogInstanceEntry nextEntryInstance = null;
								synchronized (instanceEntries) {
									nextEntryInstance = instanceEntries.pollFirst();
								}
								final LogEntry nextEntry = nextEntryInstance.entry;
								lastOffsets[nextEntryInstance.instanceIndex] = new PointerPart(
										nextEntryInstance.logSourceId, nextEntryInstance.logPath,
										nextEntry.getEndOffset());
								final ComposedLogPointer startPointer = new ComposedLogPointer(lastOffsets,
										nextEntry.getTimeStamp());
								lastOffsets[nextEntryInstance.instanceIndex] = new PointerPart(
										nextEntryInstance.logSourceId, nextEntryInstance.logPath,
										nextEntry.getEndOffset());
								final ComposedLogPointer endPointer = new ComposedLogPointer(lastOffsets,
										nextEntry.getTimeStamp());
								nextEntry.setStartOffset(startPointer);
								nextEntry.setEndOffset(endPointer);
								nextEntry.put(Event.FIELD_SOURCE_ID, nextEntryInstance.logSourceId);
								nextEntry.put(Event.FIELD_LOG_PATH, nextEntryInstance.logPath);
								instanceCounters[nextEntryInstance.instanceIndex].decrementAndGet();
								instanceEntryBuffer[nextEntryInstance.instanceIndex].release();
								try {
									running &= consumeComposedReadingResult(nextEntry);
								} catch (final Exception e) {
									terminationException = e;
									running = false;
								}
							}
						}
					} finally {
						processingSemaphore.release();
					}
				}
			} catch (final Exception e) {
				terminationException = e;
				running = false;
			}
			return running;
		}

		protected abstract boolean consumeComposedReadingResult(LogEntry entry) throws IOException;

		/**
		 * Triggered when a thread has finished reading.
		 * 
		 * @param t
		 */
		protected void finished(final SubReaderThread t) {
			synchronized (activeThreads) {
				if (t.exception != null) {
					running = false;
					terminationException = t.exception;
				}
				activeThreads.remove(t);
				if (activeThreads.size() <= 1) {
					blockedBuffer = false;
				}
			}
			// Call process to free possible blocked threads
			if (!blockedBuffer) {
				process(null, null);
			}
		}

		public void executeParallel() throws IOException {
			threadSemaphore = new Semaphore(composedLogs.size());
			synchronized (activeThreads) {
				running = true;
				instanceEntryBuffer = new Semaphore[composedLogs.size()];
				instanceCounters = new AtomicInteger[composedLogs.size()];
				for (int i = 0; i < composedLogs.size(); i++) {
					instanceEntryBuffer[i] = new Semaphore(BUFFER_SIZE_PER_THREAD);
					instanceCounters[i] = new AtomicInteger(0);
					try {
						activeThreads.add(new SubReaderThread(this, i, composedLogs.get(i), lastOffsets[i]));
					} catch (final InterruptedException e) {
						running = false;
						terminationException = e;
					}
				}
				for (final SubReaderThread t : activeThreads) {
					t.start();
				}
			}
			try {
				threadSemaphore.acquire(composedLogs.size());
			} catch (final InterruptedException e) {
				logger.error("Failed to wait for parallel threads", e);
			}
			// Complete buffered if consumer is still listening
			process(null, null);
			if (terminationException != null) {
				if (terminationException instanceof IOException) {
					throw (IOException) terminationException;
				} else if (terminationException instanceof RuntimeException) {
					throw (RuntimeException) terminationException;
				} else {
					throw new RuntimeException("Composed reading aborted with an exception", terminationException);
				}
			}
		}
	}

	private class SubReaderThread extends Thread {
		private final String logPath;
		private final long logSourceId;
		private final LogInstance logInstance;
		private final CompositionReaderExecutor executor;
		private final int logInstanceIndex;
		private final PointerPart startOffset;
		private Exception exception;

		public SubReaderThread(final CompositionReaderExecutor compositionReaderExecutor, final int logInstanceIndex,
				final LogInstance logInstance, final PointerPart startOffset) throws InterruptedException {
			super();
			this.logInstance = logInstance;
			this.logSourceId = logInstance.getLogSourceId();
			this.logPath = logInstance.getLog().getPath();
			this.executor = compositionReaderExecutor;
			this.logInstanceIndex = logInstanceIndex;
			this.startOffset = startOffset;
			executor.threadSemaphore.acquire(1);
		}

		@Override
		public void run() {
			try {
				final LogEntryReader<LogInputStream> reader = logInstance.getReader();
				final LogEntryConsumer consumer = new LogEntryConsumer() {
					@Override
					public boolean consume(final Log log, final LogPointerFactory pointerFactory, final LogEntry entry)
							throws IOException {
						return executor.process(new LogInstanceEntry(logInstanceIndex, entry, logSourceId, logPath),
								SubReaderThread.this);
					}
				};
				reader.readEntries(logInstance.getLog(), logInstance.getLogAccess(), startOffset.getOffset(), consumer);
			} catch (final Exception e) {
				logger.error("Failed to read from log instance " + logInstanceIndex + ": " + logInstance, e);
				exception = e;
			} finally {
				executor.finished(this);
				executor.threadSemaphore.release(1);
			}
		}

	}

	@Override
	public List<SeverityLevel> getSupportedSeverities() {
		final Set<SeverityLevel> levels = new HashSet<>();
		for (final LogInstance i : composedLogs) {
			levels.addAll(i.getReader().getSupportedSeverities());
		}
		final List<SeverityLevel> sortedLevels = new ArrayList<>(levels);
		Collections.sort(sortedLevels);
		return sortedLevels;
	}

	@Override
	public void readEntries(final Log log, final LogRawAccess<ComposedLogInputStream> logAccess,
			final LogPointer startOffset, final com.logsniffer.reader.LogEntryReader.LogEntryConsumer consumer)
					throws IOException, FormatException {
		// TODO navigate correctly
		final ComposedLogPointer clp = new ComposedLogPointer(new PointerPart[composedLogs.size()], new Date(0));
		for (int i = 0; i < composedLogs.size(); i++) {
			clp.getParts()[i] = new PointerPart(composedLogs.get(i).getLogSourceId(), null, null);
		}
		new CompositionReaderExecutor(clp.getParts()) {
			@Override
			protected boolean consumeComposedReadingResult(final LogEntry entry) throws IOException {
				return consumer.consume(log, logAccess, entry);
			}
		}.executeParallel();

	}

}
