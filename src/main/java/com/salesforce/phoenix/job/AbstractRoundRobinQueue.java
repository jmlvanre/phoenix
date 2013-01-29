/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.job;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * An unbounded blocking queue implementation that keeps a virtual queue of elements on per-producer
 * basis and iterates through each producer queue in round robin fashion.
 *
 */
public abstract class AbstractRoundRobinQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>{

    /**
     * @param newProducerToFront If true, new producers go to the front of the round-robin list, if false, they go to the end.
     */
    protected AbstractRoundRobinQueue(boolean newProducerToFront) {
        this.producerMap = new HashMap<Object,ProducerList<E>>();
        this.producerLists = new LinkedList<ProducerList<E>>();
        this.lock = new Object();
        this.newProducerToFront = newProducerToFront;
    }

    @Override
    public Iterator<E> iterator() {
        synchronized(lock) {
            ArrayList<E> allElements = new ArrayList<E>(this.size);
            ListIterator<ProducerList<E>> iter = this.producerLists.listIterator(this.currentProducer);
            while(iter.hasNext()) {
                ProducerList<E> tList = iter.next();
                allElements.addAll(tList.list);
            }
            return allElements.iterator();
        }
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException {
        return offer(o);
    }

    @Override
    public boolean offer(E o) {
        if (o == null)
            throw new NullPointerException();

        final Object producerKey = extractProducer(o);

        ProducerList<E> producerList = null;
        synchronized(lock) {
            producerList = this.producerMap.get(producerKey);
            if (producerList == null) {
                producerList = new ProducerList<E>(producerKey);
                this.producerMap.put(producerKey, producerList);
                this.producerLists.add(this.currentProducer, producerList);
                if (!this.newProducerToFront) {
                    incrementCurrentProducerPointer();
                }
            }
            producerList.list.add(o);
            this.size++;
            lock.notifyAll();
        }
        return true;
    }
    
    /**
     * Implementations must extracts the producer object which is used as the key to identify a unique producer.
     * (See RoundRobinQueue for an implementation uses the current thread.)
     */
    protected abstract Object extractProducer(E o);

    @Override
    public void put(E o) {
        offer(o);
    }

    @Override
    public E take() throws InterruptedException {
        synchronized(lock) {
            while (this.size == 0) {
                this.lock.wait();
            }
            E element = poll();
            assert element != null;
            return element;
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long endAt = System.currentTimeMillis() + unit.toMillis(timeout);
        synchronized(lock) {
            long waitTime = endAt - System.currentTimeMillis();
            while (this.size == 0 && waitTime > 0) {
                this.lock.wait(waitTime);
                waitTime = endAt - System.currentTimeMillis();
            }
            return poll();
        }
    }

    @Override
    public E poll() {
        synchronized(lock) {
            ListIterator<ProducerList<E>> iter = this.producerLists.listIterator(this.currentProducer);
            while (iter.hasNext()) {
                ProducerList<E> tList = iter.next();
                if (tList.list.isEmpty()) {
                    iter.remove();
                    this.producerMap.remove(tList.producer);
                    adjustCurrentProducerPointer();
                } else {
                    E element = tList.list.removeFirst();
                    this.size--;
                    assert element != null;
                    // This is the round robin part. When we take an element from the current thread's queue
                    // we move on to the next thread.
                    if (tList.list.isEmpty()) {
                        iter.remove();
                        this.producerMap.remove(tList.producer);
                        adjustCurrentProducerPointer();
                    } else {
                        incrementCurrentProducerPointer();
                    }
                    return element;
                }
            }
            assert this.size == 0;
        }
        return null;
    }

    /**
     * Polls using the given producer key.
     */
    protected E pollProducer(Object producer) {
        synchronized(lock) {
            ProducerList<E> tList = this.producerMap.get(producer);
            if (tList != null && !tList.list.isEmpty()) {
                E element = tList.list.removeFirst();
                this.size--;
                if (tList.list.isEmpty()) {
                    this.producerLists.remove(tList);
                    this.producerMap.remove(tList.producer);
                    // we need to adjust the current thread pointer in case it pointed to this thread list, which is now removed
                    adjustCurrentProducerPointer();
                }
                assert element != null;
                // Since this is only processing the current thread's work, we'll leave the
                // round-robin part alone and just return the work
                return element;
            }
        }
        return null;
    }

    @Override
    public E peek() {
        synchronized(lock) {
            ListIterator<ProducerList<E>> iter = this.producerLists.listIterator(this.currentProducer);
            while (iter.hasNext()) {
                ProducerList<E> tList = iter.next();
                if (tList.list.isEmpty()) {
                    iter.remove();
                    this.producerMap.remove(tList.producer);
                    adjustCurrentProducerPointer();
                } else {
                    E element = tList.list.getFirst();
                    assert element != null;
                    return element;
                }
            }
            assert this.size == 0;
        }
        return null;
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();

        synchronized(this.lock) {
            int originalSize = this.size;
            int drained = drainTo(c, this.size);
            assert drained == originalSize;
            assert this.size == 0;
            assert this.producerLists.isEmpty();
            assert this.producerMap.isEmpty();
            return drained;
        }
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();

        synchronized(this.lock) {
            int i = 0;
            while(i < maxElements) {
                E element = poll();
                if (element != null) {
                    c.add(element);
                    i++;
                } else {
                    break;
                }
            }
            return i;
        }
    }

    @Override
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int size() {
        synchronized(this.lock) {
            return this.size;
        }
    }
    
    private void incrementCurrentProducerPointer() {
        synchronized(lock) {
            if (this.producerLists.size() == 0) {
                this.currentProducer = 0;
            } else {
                this.currentProducer = (this.currentProducer+1)%this.producerLists.size();
            }
        }
    }
    
    /**
     * Adjusts the current pointer to a decrease in size.
     */
    private void adjustCurrentProducerPointer() {
        synchronized(lock) {
            if (this.producerLists.size() == 0) {
                this.currentProducer = 0;
            } else {
                this.currentProducer = (this.currentProducer)%this.producerLists.size();
            }
        }
    }

    private static class ProducerList<E> {
        public ProducerList(Object producer) {
            this.producer = producer;
            this.list = new LinkedList<E>();
        }
        private final Object producer;
        private final LinkedList<E> list;
    }

    private final Map<Object,ProducerList<E>> producerMap;
    private final LinkedList<ProducerList<E>> producerLists;
    private final Object lock;
    private final boolean newProducerToFront;
    private int currentProducer;
    private int size;
}