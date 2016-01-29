/*
 * Copyright (C) 2011-2016 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.central.rt;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.truth.Truth.assertThat;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.Test;

import com.github.rinde.rinsim.central.rt.AffinityTestHelper.AffinityLockInfo;
import com.github.rinde.rinsim.central.rt.AffinityTestHelper.ThreadInfo;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;

import net.openhft.affinity.Affinity;
import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.AffinityThreadFactory;

/**
 *
 * @author Rinde van Lon
 */
public class AffinityGroupThreadFactoryTest {

  static final String NAME = "testfac";

  @Test
  public void test() throws InterruptedException {
    final ExcepHandler handler = new ExcepHandler();

    assertThat(getReservations()).isEmpty();

    final AffinityGroupThreadFactory fac =
      new AffinityGroupThreadFactory(NAME, handler);
    System.out.println(AffinityLock.dumpLocks());
    assertThat(getReservations()).isEmpty();

    final Thread t0 = fac.newThread(new SleeperRunnable());
    final Thread t1 = fac.newThread(new SleeperRunnable());
    final Thread t2 = fac.newThread(new SleeperRunnable());

    assertThat(t0.getName()).isEqualTo(NAME + "-0");
    assertThat(t1.getName()).isEqualTo(NAME + "-1");
    assertThat(t2.getName()).isEqualTo(NAME + "-2");

    t0.start();
    // we have to wait until the thread is completely started and has claimed
    // the lock
    Thread.sleep(200L);
    System.out.println(AffinityLock.dumpLocks());
    assertThat(getReservations()).hasSize(1);
    final ThreadInfo ti2 = getReservations().get(0).getThreadInfo().get();
    assertThat(ti2.getCpuId()).isGreaterThan(0);
    assertThat(ti2.isAlive()).isTrue();
    assertThat(ti2.getName()).isEqualTo(NAME);

    // second thread should reuse same lock
    t1.start();
    Thread.sleep(200L);
    System.out.println(AffinityLock.dumpLocks());
    assertThat(getReservations()).hasSize(1);
    final ThreadInfo ti3 = getReservations().get(0).getThreadInfo().get();
    assertThat(ti3.getCpuId()).isGreaterThan(0);
    assertThat(ti3.isAlive()).isTrue();
    assertThat(ti3.getName()).isEqualTo(NAME);

    final Thread rogueThread1 = createThread("Rogue thread");
    rogueThread1.start();
    Thread.sleep(200L);
    System.out.println(AffinityLock.dumpLocks());

    assertThat(getReservations()).hasSize(2);
    final ThreadInfo ti3a = getReservationByName("Rogue thread");
    assertThat(ti3a.isAlive()).isTrue();
    assertThat(ti3a.getCpuId()).isGreaterThan(0);
    assertThat(ti3a.getCpuId()).isNotSameAs(ti3.getCpuId());

    // we interrupt initial thread, the lock should remain -> but should be
    // renamed! another living thread should take its place, if there is no
    // living thread the lock should be released and reclaimed when needed.
    t0.interrupt();
    Thread.sleep(200L);
    System.out.println(AffinityLock.dumpLocks());
    assertThat(getReservations()).hasSize(2);

    final ThreadInfo ti4 = getReservationByName(NAME);
    assertThat(ti4.getCpuId()).isEqualTo(ti3.getCpuId());
    assertThat(ti4.getCpuId()).isGreaterThan(0);
    assertThat(ti4.isAlive()).isTrue();

    rogueThread1.interrupt();
    Thread.sleep(200L);
    assertThat(rogueThread1.isAlive()).isFalse();
    System.out.println(AffinityLock.dumpLocks());
    assertThat(getReservations()).hasSize(1);
    // only the original lock should remain
    getReservationByName(NAME);

    t1.interrupt();
    Thread.sleep(25L);
    assertThat(getReservations()).isEmpty();

    final Thread rogueThread2 = createThread("Rogue thread 2");
    rogueThread2.start();
    t2.start();
    Thread.sleep(25L);
    System.out.println(AffinityLock.dumpLocks());

    assertThat(getReservations()).hasSize(2);
    final ThreadInfo ti5 = getReservationByName(NAME);
    assertThat(ti5.getCpuId()).isGreaterThan(0);
    assertThat(ti5.isAlive()).isTrue();
    assertThat(ti5.getName()).isEqualTo(NAME);

    final ThreadInfo ti6 = getReservationByName("Rogue thread 2");
    assertThat(ti6.getCpuId()).isEqualTo(ti3.getCpuId());
    assertThat(ti6.isAlive()).isTrue();
    assertThat(ti6.getName()).isEqualTo("Rogue thread 2");

    t2.interrupt();
    rogueThread2.interrupt();
  }

  @Test
  public void test3() throws InterruptedException {
    final ExcepHandler handler = new ExcepHandler();

    final AffinityGroupThreadFactory fac =
      new AffinityGroupThreadFactory(NAME, handler);

    final Thread b1 = fac.newThread(createBusyThread());
    final Thread b2 = fac.newThread(createBusyThread());

    b1.start();
    b2.start();
    Thread.sleep(10L);
    System.out.println(AffinityLock.dumpLocks());

    while (b1.isAlive() || b2.isAlive()) {
      Thread.sleep(100);
    }
  }

  public void test4() throws InterruptedException {

    final AffinityThreadFactory fac = new AffinityThreadFactory("aff");

    final Thread b1 = fac.newThread(createBusyThread());
    final Thread b2 = fac.newThread(createBusyThread());

    b1.start();
    b2.start();
    Thread.sleep(10L);
    System.out.println(AffinityLock.dumpLocks());

    while (b1.isAlive() || b2.isAlive()) {
      Thread.sleep(100);
    }

  }

  static Runnable createBusyThread() {
    return new Runnable() {
      @Override
      public void run() {
        while (true) {
          final List<String> strings = new ArrayList<>();
          for (int i = 0; i < 10000; i++) {
            strings.add(Integer.toBinaryString(i));
          }
        }
      }
    };
  }

  @Test
  public void test2() throws InterruptedException {

    AffinityLock.acquireLock();

    System.out.println(AffinityLock.dumpLocks());
    System.out.println(Affinity.isJNAAvailable());
    Affinity.setAffinity(2);
    Affinity.setThreadId();
    Thread.sleep(100L);

    final BitSet bs = new BitSet();
    bs.set(2);

    Affinity.getAffinityImpl().setAffinity(bs);

    System.out.println(Affinity.getCpu());
    System.out.println(Affinity.getAffinity());
    System.out.println(AffinityLock.dumpLocks());

    final Thread t = new Thread(new Runnable() {
      @Override
      public void run() {

        final BitSet affinity = new BitSet();
        affinity.set(1, true);
        affinity.set(2, true);
        System.out.println("set affinity: " + affinity);
        Affinity.setAffinity(affinity);

        System.out.println("Thread: " + Affinity.getAffinity());

        while (true) {
          try {
            Thread.sleep(1000L);
          } catch (final InterruptedException e) {
            return;
          }
        }
      }
    });
    t.start();
  }

  static Thread createThread(final String name) {
    final Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        System.out.println("Starting " + name);
        final AffinityLock lock = AffinityLock.acquireLock();
        while (true) {
          try {
            Thread.sleep(1000L);
          } catch (final InterruptedException e) {
            lock.release();
            return;
          }
        }
      }
    });
    t.setName(name);
    return t;
  }

  static List<AffinityLockInfo> getReservations() {
    return FluentIterable.from(AffinityTestHelper.getLockInfo())
        .filter(new Predicate<AffinityLockInfo>() {
          @Override
          public boolean apply(@Nullable AffinityLockInfo input) {
            return verifyNotNull(input).getThreadInfo().isPresent();
          }
        })
        .toList();
  }

  static ThreadInfo getReservationByName(final String name) {
    final List<ThreadInfo> infos =
      FluentIterable.from(AffinityTestHelper.getLockInfo())
          .filter(new Predicate<AffinityLockInfo>() {
            @Override
            public boolean apply(@Nullable AffinityLockInfo input) {
              final AffinityLockInfo in = verifyNotNull(input);
              return in.getThreadInfo().isPresent()
                  && in.getThreadInfo().get().getName().equals(name);
            }
          }).transform(new Function<AffinityLockInfo, ThreadInfo>() {
            @Nonnull
            @Override
            public ThreadInfo apply(@Nullable AffinityLockInfo input) {
              return verifyNotNull(input).getThreadInfo().get();
            }
          }).toList();

    checkArgument(infos.size() == 1,
      "There is no reservation made by a thread with name: '%s'.", name);
    return infos.get(0);
  }

  static class SleeperRunnable implements Runnable {
    @Override
    public void run() {
      try {
        while (true) {
          Thread.sleep(1000);
        }
      } catch (final InterruptedException e) {
        return;
      }
    }
  }

  static class ExcepHandler implements UncaughtExceptionHandler {
    private final Map<Thread, Throwable> exceptions;

    ExcepHandler() {
      exceptions = new LinkedHashMap<>();
    }

    @Override
    public void uncaughtException(@Nullable Thread t, @Nullable Throwable e) {
      exceptions.put(t, e);
    }
  }
}
