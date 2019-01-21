/*
 * Copyright 2018, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.theta;

import java.util.concurrent.atomic.AtomicBoolean;

import com.yahoo.memory.WritableMemory;
import com.yahoo.sketches.ResizeFactor;

/**
 * A shared sketch that is based on HeapQuickSelectSketch.
 * It reflects all data processed by a single or multiple update threads, and can serve queries at
 * any time
 *
 * @author eshcar
 */
class ConcurrentHeapQuickSelectSketch extends HeapQuickSelectSketch
    implements ConcurrentSharedThetaSketch {

  /**
   * A flag to coordinate between several propagation threads
   */
  private final AtomicBoolean sharedPropagationInProgress_;

  /**
   * Theta value of concurrent sketch
   */
  private volatile long volatileThetaLong_;

  /**
   * A snapshot of the estimated number of unique entries
   */
  private volatile double volatileEstimate_;

  /**
   * An epoch defines an interval between two resets. A propagation invoked at epoch i cannot
   * affect the sketch at epoch j>i.
   */
  private volatile long epoch_;

  /**
   * Construct a new sketch instance on the java heap.
   *
   * @param lgNomLongs <a href="{@docRoot}/resources/dictionary.html#lgNomLogs">See lgNomLongs</a>.
   * @param seed       <a href="{@docRoot}/resources/dictionary.html#seed">See seed</a>
   */
  ConcurrentHeapQuickSelectSketch(final int lgNomLongs, final long seed) {
    super(lgNomLongs, seed, 1.0F, //p
        ResizeFactor.X1, //rf,
        false); //unionGadget
    volatileThetaLong_ = Long.MAX_VALUE;
    volatileEstimate_ = 0;
    sharedPropagationInProgress_ = new AtomicBoolean(false);
  }

  //Sketch overrides

  /**
   * Gets the unique count estimate.
   * @return the sketch's best estimate of the cardinality of the input stream.
   */
  @Override
  public double getEstimate() {
    return getEstimationSnapshot();
  }

  //HeapQuickSelectSketch overrides

  /**
   * Rebuilds the hash table to remove dirty values or to reduce the size
   * to nominal entries.
   */
  @Override
  public UpdateSketch rebuild() {
    super.rebuild();
    updateEstimationSnapshot();
    return this;
  }

  /**
   * Resets this sketch back to a virgin empty state.
   * Takes care of mutual exclusion with propagation thread
   */
  @Override
  public void reset() {
    advanceEpoch();
    super.reset();
    volatileThetaLong_ = Long.MAX_VALUE;
    volatileEstimate_ = 0;
  }

  //ConcurrentSharedThetaSketch overrides

  /**
   * Converts this UpdateSketch to an ordered CompactSketch on the Java heap.
   * @return this sketch as an ordered CompactSketch on the Java heap.
   */
  @Override
  public CompactSketch compactShared() {
    return compact();
  }

  /**
   * Convert this UpdateSketch to a CompactSketch in the chosen form.
   *
   * <p>This compacting process converts the hash table form of an UpdateSketch to
   * a simple list of the valid hash values from the hash table.  Any hash values equal to or
   * greater than theta will be discarded.  The number of valid values remaining in the
   * Compact Sketch depends on a number of factors, but may be larger or smaller than
   * <i>Nominal Entries</i> (or <i>k</i>). It will never exceed 2<i>k</i>.  If it is critical
   * to always limit the size to no more than <i>k</i>, then <i>rebuild()</i> should be called
   * on the UpdateSketch prior to this.
   *
   * @param dstOrdered <a href="{@docRoot}/resources/dictionary.html#dstOrdered">See Destination Ordered</a>
   * @param dstMem     <a href="{@docRoot}/resources/dictionary.html#dstMem">See Destination Memory</a>.
   * @return this sketch as a CompactSketch in the chosen form
   */
  @Override
  public CompactSketch compactShared(final boolean dstOrdered, final WritableMemory dstMem) {
    return compact(dstOrdered, dstMem);
  }

  /**
   * Completes the propagation: end mutual exclusion block.
   * Notifies the local thread the propagation is completed
   *
   * @param localPropagationInProgress the synchronization primitive through which propagator
   *                                   notifies local thread the propagation is completed
   */
  @Override
  public void endPropagation(final AtomicBoolean localPropagationInProgress) {
    //update volatile theta, uniques estimate and propagation flag
    updateVolatileTheta();
    updateEstimationSnapshot();
    sharedPropagationInProgress_.set(false);
    if (localPropagationInProgress != null) {
      localPropagationInProgress.set(false); //clear local propagation flag
    }
  }

  /**
   * Returns a (fresh) estimation of the number of unique entries
   * @return a (fresh) estimation of the number of unique entries
   */
  @Override
  public double getEstimationSnapshot() {
    return volatileEstimate_;
  }

  /**
   * Returns the number of storage bytes required for this Sketch in its current state.
   * @param compact if true, returns the bytes required for compact form.
   * If this sketch is already in compact form this parameter is ignored.
   * @return the number of storage bytes required for this sketch
   */
  @Override
  public int getSharedCurrentBytes(final boolean compact) {
    return getCurrentBytes(compact);
  }

  /**
   * Gets the approximate lower error bound given the specified number of Standard Deviations.
   * This will return getEstimate() if isEmpty() is true.
   *
   * @param numStdDev <a href="{@docRoot}/resources/dictionary.html#numStdDev">See Number of Standard Deviations</a>
   * @return the lower bound.
   */
  @Override
  public double getSharedLowerBound(final int numStdDev) {
    return getLowerBound(numStdDev);
  }

  /**
   * Returns the number of entries that have been retained by the sketch.
   * @param valid if true, returns the number of valid entries, which are less than theta and used
   * for estimation.
   * Otherwise, return the number of all entries, valid or not, that are currently in the internal
   * sketch cache.
   * @return the number of retained entries
   */
  @Override
  public int getSharedRetainedEntries(final boolean valid) {
    return getRetainedEntries(valid);
  }

  /**
   * Gets the approximate upper error bound given the specified number of Standard Deviations.
   * This will return getEstimate() if isEmpty() is true.
   *
   * @param numStdDev <a href="{@docRoot}/resources/dictionary.html#numStdDev">See Number of Standard Deviations</a>
   * @return the upper bound.
   */
  @Override
  public double getSharedUpperBound(final  int numStdDev) {
    return getUpperBound(numStdDev);
  }

  /**
   * Returns the value of the volatile theta manged by the shared sketch
   * @return the value of the volatile theta manged by the shared sketch
   */
  @Override
  public long getVolatileTheta() {
    return volatileThetaLong_;
  }

  /**
   * Returns true if a propagation is in progress, otherwise false
   * @return an indication of whether there is a pending propagation in progress
   */
  @Override
  public boolean isPropagationInProgress() {
    return sharedPropagationInProgress_.get();
  }

  /**
   * Returns true if the this sketch's internal data structure is backed by direct (off-heap)
   * Memory.
   * @return true if the this sketch's internal data structure is backed by direct (off-heap)
   * Memory.
   */
  @Override
  public boolean isSharedDirect() {
    return isDirect();
  }

  /**
   * Returns whether the shared sketch is empty
   * @return whether the shared sketch is empty
   */
  @Override
  public boolean isSharedEmpty() {
    return isEmpty();
  }

  /**
   * Returns true if the sketch is Estimation Mode (as opposed to Exact Mode).
   * This is true if theta &lt; 1.0 AND isEmpty() is false.
   *
   * @return true if the sketch is in estimation mode.
   */
  @Override
  public boolean isSharedEstimationMode() {
    return isEstimationMode();
  }

  /**
   * Propagates the given sketch or hash value into this sketch
   *
   * @param localPropagationInProgress the flag to be updated when done
   * @param sketchIn                   any Theta sketch with the data
   * @param singleHash                 a single hash value
   */
  @Override
  public void propagate(final AtomicBoolean localPropagationInProgress,
      final Sketch sketchIn, final long singleHash) {
    final long epoch = epoch_;
    final long k = 1 << getLgNomLongs();
    if ((singleHash != NOT_SINGLE_HASH)             //namely, is a single hash
        && (getRetainedEntries(false) < (2 * k))) { //and a small sketch then propagate myself (blocking)
      startPropagation();
      if (!validateEpoch(epoch)) {
        endPropagation(null); // do not change local flag
        return;
      }
      updateSingle(singleHash);
      endPropagation(localPropagationInProgress);
      return;
    }
    // otherwise, be nonblocking, let background thread do the work
    final ConcurrentBackgroundThetaPropagation job =
        new ConcurrentBackgroundThetaPropagation(this, localPropagationInProgress, sketchIn, singleHash,
            epoch);
    ConcurrentBackgroundThetaPropagation.propagationExecutorService.execute(job);
  }

  /**
   * Rebuilds the hash table to remove dirty values or to reduce the size
   * to nominal entries.
   */
  @Override
  public UpdateSketch rebuildShared() {
    return rebuild();
  }

  /**
   * Resets the content of the shared sketch to an empty sketch
   */
  @Override
  public void resetShared() {
    reset();
  }

  /**
   * Serialize this sketch to a byte array form.
   * @return byte array of this sketch
   */
  @Override
  public byte[] sharedToByteArray() {
    return toByteArray();
  }

  /**
   * Ensures mutual exclusion. No other thread can update the shared sketch while propagation is
   * in progress
   */
  @Override
  public void startPropagation() {
    while (!sharedPropagationInProgress_.compareAndSet(false, true)) {
    } //busy wait till free
  }

  /**
   * Updates the estimation of the number of unique entries by capturing a snapshot of the sketch
   * data, namely, volatile theta and the num of valid entries in the sketch
   */
  @Override
  public void updateEstimationSnapshot() {
    volatileEstimate_ = super.getEstimate();
  }

  /**
   * Updates the shared sketch with the given hash
   * @param hash to be propagated to the shared sketch
   */
  @Override
  public void updateSingle(final long hash) {
    hashUpdate(hash);
  }

  /**
   * Updates the value of the volatile theta by extracting it from the underlying sketch managed
   * by the shared sketch
   */
  @Override
  public void updateVolatileTheta() {
    volatileThetaLong_ = getThetaLong();
  }

  /**
   * Validates the shared sketch is in the context of the given epoch
   *
   * @param epoch the epoch number to be validates
   * @return true iff the shared sketch is in the context of the given epoch
   */
  @Override
  public boolean validateEpoch(final long epoch) {
    return epoch_ == epoch;
  }

  //restricted

  /**
   * Advances the epoch while there is no background propagation
   * This ensures a propagation invoked before the reset cannot affect the sketch after the reset
   * is completed.
   */
  private void advanceEpoch() {
    startPropagation();
    //noinspection NonAtomicOperationOnVolatileField
    // this increment of a volatile field is done within the scope of the propagation
    // synchronization and hence is done by a single thread
    epoch_++;
    endPropagation(null);
  }
}
