/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.objectmodel;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Word;

/**
 * Constants used to implement thin locks.
 * A portion of a word, either in the object header
 * or in some other location, is used to provide light weight
 * synchronization operations. This class defines
 * how the bits available for thin locks are allocated.
 * Either a lock is in fat state, in which case it looks like
 * 1Z..Z where Z..Z is the id of a heavy lock, or it is in
 * thin state in which case it looks like 0I..IC..C where
 * I is the thread id of the thread that owns the lock and
 * C is the recursion count of the lock.
 * <pre>
 * aaaaTTTTTTTTTTbbbbb
 * JavaHeader.NUM_THIN_LOCK_BITS = # of T's
 * JavaHeader.THIN_LOCK_SHIFT = # of b's
 * </pre>
 */
public interface ThinLockConstants extends SizeConstants {

  int TL_NUM_BITS_TID = RVMThread.LOG_MAX_THREADS;
  int TL_NUM_BITS_RC = JavaHeader.NUM_THIN_LOCK_BITS - TL_NUM_BITS_TID - 1;

  int TL_LOCK_COUNT_SHIFT = JavaHeader.THIN_LOCK_SHIFT;
  int TL_THREAD_ID_SHIFT = TL_LOCK_COUNT_SHIFT + TL_NUM_BITS_RC;
  int TL_LOCK_ID_SHIFT = JavaHeader.THIN_LOCK_SHIFT;

  int TL_LOCK_COUNT_UNIT = 1 << TL_LOCK_COUNT_SHIFT;

  Word TL_LOCK_COUNT_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - TL_NUM_BITS_RC).lsh(TL_LOCK_COUNT_SHIFT);
  Word TL_THREAD_ID_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - TL_NUM_BITS_TID).lsh(TL_THREAD_ID_SHIFT);
  Word TL_LOCK_ID_MASK =
      Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - (TL_NUM_BITS_RC + TL_NUM_BITS_TID)).lsh(TL_LOCK_ID_SHIFT);
  Word TL_FAT_LOCK_MASK = Word.one().lsh(JavaHeader.THIN_LOCK_SHIFT + TL_NUM_BITS_RC + TL_NUM_BITS_TID);
  Word TL_UNLOCK_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - JavaHeader
      .NUM_THIN_LOCK_BITS).lsh(JavaHeader.THIN_LOCK_SHIFT).not();
  
  // BL status bits:
  // 00 -> thin biasable, and biased if TID is non-zero
  // 01 -> thin unbiasable
  // 10 -> fat unbiasable
  
  int BL_NUM_BITS_STAT = 2;
  int BL_NUM_BITS_TID = RVMThread.LOG_MAX_THREADS;
  int BL_NUM_BITS_RC = JavaHeader.NUM_THIN_LOCK_BITS - BL_NUM_BITS_TID - BL_NUM_BITS_STAT;

  int BL_LOCK_COUNT_SHIFT = JavaHeader.THIN_LOCK_SHIFT;
  int BL_THREAD_ID_SHIFT = BL_LOCK_COUNT_SHIFT + BL_NUM_BITS_RC;
  int BL_STAT_SHIFT = BL_THREAD_ID_SHIFT + BL_NUM_BITS_TID;
  int BL_LOCK_ID_SHIFT = JavaHeader.THIN_LOCK_SHIFT;
  
  int BL_LOCK_COUNT_UNIT = 1 << BL_LOCK_COUNT_SHIFT;

  Word BL_LOCK_COUNT_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - BL_NUM_BITS_RC).lsh(BL_LOCK_COUNT_SHIFT);
  Word BL_THREAD_ID_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - BL_NUM_BITS_TID).lsh(BL_THREAD_ID_SHIFT);
  Word BL_LOCK_ID_MASK =
      Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - (BL_NUM_BITS_RC + BL_NUM_BITS_TID)).lsh(BL_LOCK_ID_SHIFT);
  Word BL_STAT_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - BL_NUM_BITS_TID).lsh(BL_STAT_SHIFT);
  Word BL_UNLOCK_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - JavaHeader
      .NUM_THIN_LOCK_BITS).lsh(JavaHeader.THIN_LOCK_SHIFT).not();
  
  Word BL_STAT_BIASABLE = Word.fromIntSignExtend(0).lsh(BL_STAT_SHIFT);
  Word BL_STAT_THIN = Word.fromIntSignExtend(1).lsh(BL_STAT_SHIFT);
  Word BL_STAT_FAT = Word.fromIntSignExtend(2).lsh(BL_STAT_SHIFT);
  Word BL_STAT_THIN_WAIT = Word.fromIntSignExtend(3).lsh(BL_STAT_SHIFT);

  // TLF status bits:
  // 00 -> thin, no waiters
  // 01 -> thin, waiters
  // 10 -> fat
  
  int TLF_NUM_BITS_STAT = 2;
  int TLF_NUM_BITS_TID = RVMThread.LOG_MAX_THREADS;
  int TLF_NUM_BITS_RC = JavaHeader.NUM_THIN_LOCK_BITS - TLF_NUM_BITS_TID - TLF_NUM_BITS_STAT;

  int TLF_LOCK_COUNT_SHIFT = JavaHeader.THIN_LOCK_SHIFT;
  int TLF_THREAD_ID_SHIFT = TLF_LOCK_COUNT_SHIFT + TLF_NUM_BITS_RC;
  int TLF_STAT_SHIFT = TLF_THREAD_ID_SHIFT + TLF_NUM_BITS_TID;
  int TLF_LOCK_ID_SHIFT = JavaHeader.THIN_LOCK_SHIFT;
  
  int TLF_LOCK_COUNT_UNIT = 1 << TLF_LOCK_COUNT_SHIFT;

  Word TLF_LOCK_COUNT_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - TLF_NUM_BITS_RC).lsh(TLF_LOCK_COUNT_SHIFT);
  Word TLF_THREAD_ID_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - TLF_NUM_BITS_TID).lsh(TLF_THREAD_ID_SHIFT);
  Word TLF_LOCK_ID_MASK =
      Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - (TLF_NUM_BITS_RC + TLF_NUM_BITS_TID)).lsh(TLF_LOCK_ID_SHIFT);
  Word TLF_STAT_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - TLF_NUM_BITS_TID).lsh(TLF_STAT_SHIFT);
  Word TLF_UNLOCK_MASK = Word.fromIntSignExtend(-1).rshl(BITS_IN_ADDRESS - JavaHeader
      .NUM_THIN_LOCK_BITS).lsh(JavaHeader.THIN_LOCK_SHIFT).not();
  
  Word TLF_STAT_THIN = Word.fromIntSignExtend(0).lsh(TLF_STAT_SHIFT);
  Word TLF_STAT_THIN_WAIT = Word.fromIntSignExtend(1).lsh(TLF_STAT_SHIFT);
  Word TLF_STAT_FAT = Word.fromIntSignExtend(2).lsh(TLF_STAT_SHIFT);
}

