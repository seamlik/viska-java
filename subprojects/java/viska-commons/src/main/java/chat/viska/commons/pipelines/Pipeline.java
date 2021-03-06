/*
 * Copyright (C) 2017 Kai-Chung Yan (殷啟聰)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package chat.viska.commons.pipelines;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.annotations.SchedulerSupport;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.processors.FlowableProcessor;
import io.reactivex.processors.PublishProcessor;
import io.reactivex.schedulers.Schedulers;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.nullness.qual.Nullable;
import rxbeans.ExceptionCaughtEvent;
import rxbeans.MutableProperty;
import rxbeans.Property;
import rxbeans.StandardObject;
import rxbeans.StandardProperty;

/**
 * Serial container for a series of data processors (a.k.a {@link Pipe}s). This
 * class represents a full duplex pipeline where the reading and writing is
 * happening at the same time in 2 different {@link Thread}s.
 * For any moment, there is only one reading thread and one writing thread
 * running. As a result, Pipes can be dynamically added to, removed from or
 * replaced in the Pipeline while it is running. The manipulations to these
 * Pipes will only happen when neither of the reading or writing thread is
 * running.
 *
 * <p>Because of the multi-thread nature, all methods of this class are
 * non-blocking, and both the Pipes and the Pipeline must be designed as
 * thread-safe.</p>
 *
 * <p>Beware that although a {@link Pipe} is safe to invoke any methods of this
 * class, it must not wait for the operation to finish, otherwise expect
 * deadlocks.</p>
 *
 * <p>{@link Pipe}s are uniquely named, but multiple unnamed {@link Pipe}s are allowed.</p>
 *
 * <p>This type emits the following events:</p>
 *
 * <ul>
 *   <li>{@link ExceptionCaughtEvent}</li>
 * </ul>
 * @param <I> Type of the inbound output.
 * @param <O> Type of the outbound output.
 */
@ThreadSafe
public class Pipeline<I, O> extends StandardObject implements Iterable<Map.Entry<String, Pipe>> {

  /**
   * States of a {@link Pipeline}.
   */
  public enum State {

    /**
     * Indicates a {@link Pipeline} is running.
     */
    RUNNING,

    /**
     * Indicates a {@link Pipeline} has stopped.
     */
    STOPPED
  }

  @GuardedBy("pipeLock")
  private final LinkedList<Map.Entry<String, Pipe>> pipes = new LinkedList<>();
  private final FlowableProcessor<I> inboundStream = PublishProcessor.<I>create().toSerialized();
  private final FlowableProcessor<O> outboundStream = PublishProcessor.<O>create().toSerialized();
  private final BlockingQueue<Object> readQueue = new LinkedBlockingQueue<>();
  private final BlockingQueue<Object> writeQueue = new LinkedBlockingQueue<>();
  private final ReadWriteLock pipeLock = new ReentrantReadWriteLock(true);
  private final MutableProperty<State> state = new StandardProperty<>(State.STOPPED);
  private final CompositeDisposable taskTokens = new CompositeDisposable();

  private void processObject(final Object obj, final boolean isReading) {
    final ListIterator<Map.Entry<String, Pipe>> iterator = isReading
        ? pipes.listIterator()
        : pipes.listIterator(pipes.size());
    final List<Object> cache = new ArrayList<>();
    cache.add(obj);
    while (isReading ? iterator.hasNext() : iterator.hasPrevious()) {
      final Pipe pipe = isReading
          ? iterator.next().getValue()
          : iterator.previous().getValue();
      final List<Object> toForward = new ArrayList<>();
      for (Object it : cache) {
        final List<Object> out = new ArrayList<>();
        try {
          if (isReading) {
            pipe.onReading(this, it, out);
          } else {
            pipe.onWriting(this, it, out);
          }
        } catch (Exception cause) {
          processException(iterator, cause, isReading);
          return;
        }
        toForward.addAll(out);
      }
      if (toForward.size() == 0) {
        return;
      } else {
        cache.clear();
        cache.addAll(toForward);
      }
    }
    for (Object it : cache) {
      try {
        if (isReading) {
          inboundStream.onNext((I) it);
        } else {
          outboundStream.onNext((O) it);
        }
      } catch (ClassCastException ex) {
        continue;
      }
    }
  }

  private void processException(ListIterator<Map.Entry<String, Pipe>> iterator,
                                Exception cause,
                                boolean isReading) {
    while (isReading ? iterator.hasNext() : iterator.hasPrevious()) {
      final Pipe pipe = isReading ? iterator.next().getValue() : iterator.previous().getValue();
      try {
        if (isReading) {
          pipe.catchInboundException(this, cause);
        } else {
          pipe.catchOutboundException(this, cause);
        }
        return;
      } catch (Exception rethrown) {
        cause = rethrown;
      }
    }
    triggerEvent(new ExceptionCaughtEvent(this, cause));
  }

  @Nullable
  private ListIterator<Map.Entry<String, Pipe>> getIteratorOf(final String name) {
    if (StringUtils.isBlank(name)) {
      return null;
    }
    final ListIterator<Map.Entry<String, Pipe>> iterator = pipes.listIterator();
    while (iterator.hasNext()) {
      Map.Entry entry = iterator.next();
      if (entry.getKey().equals(name)) {
        iterator.previous();
        return iterator;
      }
    }
    return null;
  }

  @Nullable
  private ListIterator<Map.Entry<String, Pipe>> getIteratorOf(@Nullable final Pipe pipe) {
    if (pipe == null) {
      return null;
    }
    final ListIterator<Map.Entry<String, Pipe>> iterator = pipes.listIterator();
    while (iterator.hasNext()) {
      Map.Entry entry = iterator.next();
      if (entry.getValue().equals(pipe)) {
        iterator.previous();
        return iterator;
      }
    }
    return null;
  }

  /**
   * Gets the state.
   */
  public Property<State> stateProperty() {
    return state;
  }

  /**
   * Starts the pipeline.
   */
  public void start() {
    state.getAndDo(state -> {
      if (state == State.RUNNING) {
        return;
      }
      final Completable readTask = Completable.fromAction(() -> {
        while (true) {
          if (this.state.get() != State.RUNNING) {
            return;
          }
          final Object obj = readQueue.take();
          pipeLock.readLock().lockInterruptibly();
          processObject(obj, true);
          pipeLock.readLock().unlock();
        }
      });
      final Completable writeTask = Completable.fromAction(() -> {
        while (true) {
          if (this.state.get() != State.RUNNING) {
            return;
          }
          final Object obj = writeQueue.take();
          pipeLock.readLock().lockInterruptibly();
          processObject(obj, false);
          pipeLock.readLock().unlock();
        }
      });
      this.state.change(State.RUNNING);
      taskTokens.add(readTask.onErrorComplete().subscribeOn(Schedulers.io()).subscribe());
      taskTokens.add(writeTask.onErrorComplete().subscribeOn(Schedulers.io()).subscribe());
    });
  }

  /**
   * Stops the Pipeline immediately by killing the reading and writing threads.
   * The data being processed at the time will be abandoned.
   */
  public void stopNow() {
    state.getAndDo(state -> {
      if (state == State.STOPPED) {
        return;
      }
      taskTokens.clear();
      this.state.change(State.STOPPED);
    });
  }

  public void addTowardsInboundEnd(final String previous, final String name, final Pipe pipe) {
    Validate.notBlank(previous);
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        if (getIteratorOf(name) != null) {
          throw new IllegalArgumentException("Name collision: " + name);
        }
        ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(previous);
        if (iterator == null) {
          throw new NoSuchElementException(previous);
        }
        iterator.next();
        iterator.add(new AbstractMap.SimpleImmutableEntry<>(name, pipe));
        pipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  public void addTowardsInboundEnd(final Pipe previous,  final String name, final Pipe pipe) {
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        if (getIteratorOf(name) != null) {
          throw new IllegalArgumentException("Name collision: " + name);
        }
        ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(previous);
        if (iterator == null) {
          throw new NoSuchElementException();
        }
        iterator.next();
        iterator.add(new AbstractMap.SimpleImmutableEntry<>(name, pipe));
        pipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  public void addTowardsOutboundEnd(final String next, final String name, final Pipe pipe) {
    Validate.notBlank(next);
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        if (getIteratorOf(name) != null) {
          throw new IllegalArgumentException("Name collision: " + name);
        }
        ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(next);
        if (iterator == null) {
          throw new NoSuchElementException(next);
        }
        iterator.add(new AbstractMap.SimpleImmutableEntry<>(name, pipe));
        pipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  public void addTowardsOutboundEnd(final Pipe next, final String name, final Pipe pipe) {
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        if (getIteratorOf(name) != null) {
          throw new IllegalArgumentException("Name collision: " + name);
        }
        ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(next);
        if (iterator == null) {
          throw new NoSuchElementException();
        }
        iterator.add(new AbstractMap.SimpleImmutableEntry<>(name, pipe));
        pipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  /**
   * Adds a {@link Pipe} at the outbound end.
   */
  public void addAtOutboundEnd(final String name, final Pipe pipe) {
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        if (getIteratorOf(name) != null) {
          throw new IllegalArgumentException("Name collision: " + name);
        }
        pipes.addFirst(new AbstractMap.SimpleImmutableEntry<>(name, pipe));
        pipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  /**
   * Adds a {@link Pipe} at the inbound end.
   */
  public void addAtInboundEnd(final String name, final Pipe pipe) {
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        if (getIteratorOf(name) != null) {
          throw new IllegalArgumentException("Name collision: " + name);
        }
        pipes.addLast(new AbstractMap.SimpleImmutableEntry<>(name, pipe));
        pipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  /**
   * Clears the read queue and write queue.
   */
  public void clearQueues() {
    readQueue.clear();
    writeQueue.clear();
  }

  /**
   * Removes all {@link Pipe}s.
   */
  public void removeAll() {
    Completable.fromAction(() -> {
      pipeLock.writeLock().lockInterruptibly();
      for (Map.Entry<String, Pipe> entry : pipes) {
        entry.getValue().onRemovedFromPipeline(this);
      }
      pipes.clear();
      pipeLock.writeLock().unlock();
    }).onErrorComplete().subscribeOn(Schedulers.io()).subscribe();
  }

  /**
   * Removes a {@link Pipe}.
   */
  @SchedulerSupport(SchedulerSupport.IO)
  public Maybe<Pipe> remove(final String name) {
    Validate.notBlank(name);
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      final Pipe pipe;
      pipeLock.writeLock().lockInterruptibly();
      try {
        final ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(name);
        if (iterator == null) {
          return null;
        }
        pipe = iterator.next().getValue();
        iterator.remove();
        pipe.onRemovedFromPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
      return pipe;
    }).subscribeOn(Schedulers.io());
  }

  /**
   * Removes a {@link Pipe}. Fails silently if the specified {@link Pipe} does
   * not exist in the pipeline.
   */
  @SchedulerSupport(SchedulerSupport.IO)
  public Maybe<Pipe> remove(final Pipe pipe) {
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        final ListIterator iterator = getIteratorOf(pipe);
        if (iterator == null) {
          return null;
        }
        iterator.next(); // For the remove() to work
        iterator.remove();
        pipe.onRemovedFromPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
      return pipe;
    }).subscribeOn(Schedulers.io());
  }

  @SchedulerSupport(SchedulerSupport.IO)
  public Maybe<Pipe> removeAtOutboundEnd() {
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      pipeLock.writeLock().lockInterruptibly();
      final Map.Entry<String, Pipe> entry = pipes.pollFirst();
      if (entry == null) {
        return null;
      } else {
        entry.getValue().onRemovedFromPipeline(this);
      }
      pipeLock.writeLock().unlock();
      return entry.getValue();
    }).subscribeOn(Schedulers.io());
  }

  @SchedulerSupport(SchedulerSupport.IO)
  public Maybe<Pipe> removeAtInboundEnd() {
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      pipeLock.writeLock().lockInterruptibly();
      final Map.Entry<String, Pipe> entry = pipes.pollLast();
      if (entry == null) {
        return null;
      } else {
        entry.getValue().onRemovedFromPipeline(this);
      }
      pipeLock.writeLock().unlock();
      return entry.getValue();
    }).subscribeOn(Schedulers.io());
  }

  @SchedulerSupport(SchedulerSupport.IO)
  public Maybe<Pipe> replace(final String name, final Pipe newPipe) {
    Validate.notBlank(name);
    return Maybe.fromCallable(() -> {
      pipeLock.writeLock().lockInterruptibly();
      final Pipe oldPipe;
      try {
        final ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(name);
        if (iterator == null) {
          throw new NoSuchElementException(name);
        }
        oldPipe = iterator.next().getValue();
        iterator.set(new AbstractMap.SimpleImmutableEntry<>(
            name,
            newPipe
        ));
        oldPipe.onRemovedFromPipeline(this);
        newPipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
      return oldPipe;
    }).subscribeOn(Schedulers.io());
  }

  @SchedulerSupport(SchedulerSupport.IO)
  public Maybe<Pipe> replace(final Pipe oldPipe, final Pipe newPipe) {
    return Maybe.fromCallable(() -> {
      pipeLock.writeLock().lockInterruptibly();
      try {
        final ListIterator<Map.Entry<String, Pipe>> iterator = getIteratorOf(oldPipe);
        if (iterator == null) {
          throw new NoSuchElementException();
        }
        Map.Entry<String, Pipe> oldEntry = iterator.next();
        iterator.set(new AbstractMap.SimpleImmutableEntry<>(
            oldEntry.getKey(),
            newPipe
        ));
        oldPipe.onRemovedFromPipeline(this);
        newPipe.onAddedToPipeline(this);
      } finally {
        pipeLock.writeLock().unlock();
      }
      return oldPipe;
    }).subscribeOn(Schedulers.io());
  }

  /**
   * Feeds a data at the outbound end.
   * @throws IllegalStateException If the pipeline is disposed of.
   */
  public void read(final Object obj) {
    readQueue.add(obj);
  }

  /**
   * Feeds a data at the inbound end.
   * @throws IllegalStateException If the pipeline is disposed of.
   */
  public void write(final Object obj) {
    writeQueue.add(obj);
  }

  @SchedulerSupport(SchedulerSupport.NONE)
  public Maybe<Pipe> get(@Nullable final String name) {
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      pipeLock.readLock().lockInterruptibly();
      for (Map.Entry<String, Pipe> it : pipes) {
        if (it.getKey().equals(name)) {
          pipeLock.readLock().unlock();
          return it.getValue();
        }
      }
      return null;
    });
  }

  @SchedulerSupport(SchedulerSupport.NONE)
  public Maybe<Pipe> getOutboundEnd() {
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      pipeLock.readLock().lockInterruptibly();
      final Map.@Nullable Entry<String, Pipe> entry = pipes.peekFirst();
      return entry == null ? null : entry.getValue();
    });
  }

  @SchedulerSupport(SchedulerSupport.NONE)
  public Maybe<Pipe> getInboundEnd() {
    return Maybe.fromCallable((Callable<@Nullable Pipe>) () -> {
      pipeLock.readLock().lockInterruptibly();
      final Map.@Nullable Entry<String, Pipe> entry = pipes.peekLast();
      return entry == null ? null : entry.getValue();
    });
  }

  public Flowable<I> getInboundStream() {
    return inboundStream;
  }

  public Flowable<O> getOutboundStream() {
    return outboundStream;
  }

  @Override
  public Iterator<Map.Entry<String, Pipe>> iterator() {
    return pipes.iterator();
  }
}