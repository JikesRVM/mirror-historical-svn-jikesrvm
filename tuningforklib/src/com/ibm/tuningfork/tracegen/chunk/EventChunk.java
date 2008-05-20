/*
 * This file is part of the Tuning Fork Visualization Platform
 *  (http://sourceforge.net/projects/tuningforkvp)
 *
 * Copyright (c) 2005 - 2008 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 */

package com.ibm.tuningfork.tracegen.chunk;

import com.ibm.tuningfork.tracegen.types.EventType;

public class EventChunk extends Chunk {

    public static final int EVENT_TYPE_ID = 5;
    public static final int FEEDLET_ID_OFFSET = Chunk.DATA_OFFSET;
    public static final int SEQUENCE_NUMBER_OFFSET = Chunk.DATA_OFFSET + 4;
    public static final int EVENT_DATA_OFFSET = Chunk.DATA_OFFSET + 8;
    private int numberOfEvents = 0;
    private long firstTimeStamp = 0;
    public static final long TIMESTAMP_FLUSH_DELTA = 1000000000; // 1 second
								    // assuming
								    // ticks are
								    // ns
    protected final boolean autoFlush;

    public EventChunk(boolean autoFlush) {
	super(EVENT_TYPE_ID);
	seek(EVENT_DATA_OFFSET);
	this.autoFlush = autoFlush;
    }

    public void reset(int feedletIndex, int sequenceNumber) {
	super.resetImpl();
	seek(FEEDLET_ID_OFFSET);
	addInt(feedletIndex);
	addInt(sequenceNumber);
	seek(EVENT_DATA_OFFSET);
	numberOfEvents = 0;
	firstTimeStamp = 0;
    }

    protected final boolean canAddEvent(long timeStamp, int requiredSpace) {
	if (!hasRoom(requiredSpace)) {
	    return false;
	}
	if (!autoFlush) {
	    return true;
	}
	if (firstTimeStamp == 0) {
	    firstTimeStamp = timeStamp;
	}
	return ((timeStamp - firstTimeStamp) < TIMESTAMP_FLUSH_DELTA);
    }

    public boolean addEvent(long timeStamp, EventType et) {
	int required = ENCODING_SPACE_LONG + ENCODING_SPAGE_INT;
	if (!canAddEvent(timeStamp, required)) {
	    return false;
	}
	addLong(timeStamp);
	addInt(et.getIndex());
	numberOfEvents++;
	return true;
    }

    public boolean addEvent(long timeStamp, EventType et, int v) {
	int required = ENCODING_SPACE_LONG + ENCODING_SPAGE_INT
		+ ENCODING_SPAGE_INT;
	if (!canAddEvent(timeStamp, required)) {
	    return false;
	}
	addLong(timeStamp);
	addInt(et.getIndex());
	addInt(v);
	numberOfEvents++;
	return true;
    }

    public boolean addEvent(long timeStamp, EventType et, long v) {
	int required = ENCODING_SPACE_LONG + ENCODING_SPAGE_INT
		+ ENCODING_SPACE_LONG;
	if (!canAddEvent(timeStamp, required)) {
	    return false;
	}
	addLong(timeStamp);
	addInt(et.getIndex());
	addLong(v);
	numberOfEvents++;
	return true;
    }

    public boolean addEvent(long timeStamp, EventType et, double v) {
	int required = ENCODING_SPACE_LONG + ENCODING_SPAGE_INT
		+ ENCODING_SPACE_DOUBLE;
	if (!canAddEvent(timeStamp, required)) {
	    return false;
	}
	addLong(timeStamp);
	addInt(et.getIndex());
	addDouble(v);
	numberOfEvents++;
	return true;
    }

    public boolean addEvent(long timeStamp, EventType et, String v) {
	int required = ENCODING_SPACE_LONG + ENCODING_SPAGE_INT
		+ encodingSpace(v);
	if (!canAddEvent(timeStamp, required)) {
	    return false;
	}
	addLong(timeStamp);
	addInt(et.getIndex());
	addString(v);
	numberOfEvents++;
	return true;
    }

    public boolean addEvent(long timeStamp, EventType et, int[] idata,
	    long[] ldata, double[] ddata, String[] sdata) {
	int ilen = (idata == null) ? 0 : idata.length;
	int llen = (ldata == null) ? 0 : ldata.length;
	int dlen = (ddata == null) ? 0 : ddata.length;
	int slen = (sdata == null) ? 0 : sdata.length;
	int required = ENCODING_SPACE_LONG + ENCODING_SPAGE_INT + ilen
		* ENCODING_SPAGE_INT + llen * ENCODING_SPACE_LONG + dlen
		* ENCODING_SPACE_DOUBLE;
	for (int i = 0; i < slen; i++) {
	    required += encodingSpace(sdata[i]);
	}
	if (!canAddEvent(timeStamp, required)) {
	    return false;
	}
	addLong(timeStamp);
	addInt(et.getIndex());
	for (int i = 0; i < ilen; i++) {
	    addInt(idata[i]);
	}
	for (int i = 0; i < llen; i++) {
	    addLong(ldata[i]);
	}
	for (int i = 0; i < dlen; i++) {
	    addDouble(ddata[i]);
	}
	for (int i = 0; i < slen; i++) {
	    addString(sdata[i]);
	}
	numberOfEvents++;
	return true;
    }

}
