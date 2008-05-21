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

import com.ibm.tuningfork.tracegen.types.EventAttribute;
import com.ibm.tuningfork.tracegen.types.EventType;

public class EventTypeChunk extends Chunk {

    public static final int EVENT_TYPE_ID = 4;
    public static final int EVENT_TYPE_OFFSET = Chunk.DATA_OFFSET;
    public static final int EVENT_DATA_OFFSET = EVENT_TYPE_OFFSET + 4;
    private int numberOfEventTypes = 0;

    public EventTypeChunk() {
	super(EVENT_TYPE_ID);
	seek(EVENT_DATA_OFFSET);
    }

    public boolean hasData() {
	return numberOfEventTypes > 0;
    }

    public boolean add(EventType et) {
	int guess = ENCODING_SPACE_INT + et.getName().length()
		+ et.getDescription().length() + ENCODING_SPACE_INT * 4;
	for (int i = 0; i < et.getNumberOfAttributes(); i++) {
	    EventAttribute ea = et.getAttribute(i);
	    guess += ea.getName().length();
	    guess += ea.getDescription().length();
	}
	if (!hasRoom(guess)) {
	    return false;
	}
	int savedPosition = getPosition();
	addInt(et.getIndex());
	if (!addString(et.getName())) {
	    seek(savedPosition);
	    return false;
	}
	if (!addString(et.getDescription())) {
	    seek(savedPosition);
	    return false;
	}
	addInt(et.getNumberOfInts());
	addInt(et.getNumberOfLongs());
	addInt(et.getNumberOfDoubles());
	addInt(et.getNumberOfStrings());
	for (int i = 0; i < et.getNumberOfAttributes(); i++) {
	    EventAttribute ea = et.getAttribute(i);
	    if (!addString(ea.getName())) {
		seek(savedPosition);
		return false;
	    }
	    if (!addString(ea.getDescription())) {
		seek(savedPosition);
		return false;
	    }
	}
	numberOfEventTypes++;
	return true;
    }

    public void close() {
	int pos = getPosition();
	seek(EVENT_TYPE_OFFSET);
	addInt(numberOfEventTypes);
	seek(pos);
	numberOfEventTypes = 0;
	super.close();
    }

}
