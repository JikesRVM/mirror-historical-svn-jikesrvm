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


public class Chunk extends RawChunk {

    private static final int MAGIC_WORD_1 = 0xdeadbeef;
    private static final int MAGIC_WORD_2 = 0xcafebabe;
    private static final int LENGTH_OFFSET = 8;
    private static final int CHUNK_TYPE_OFFSET = 12;
    protected static final int DATA_OFFSET = 16;

    public Chunk(int chunkType) {
	addInt(MAGIC_WORD_1);
	addInt(MAGIC_WORD_2);
	seek(CHUNK_TYPE_OFFSET);
	addInt(chunkType);
	seek(DATA_OFFSET);
    }

    public void close() {
	int pos = getPosition();
	int bodyLength = pos - DATA_OFFSET;
	seek(LENGTH_OFFSET);
	addInt(bodyLength);
	seek(pos);
	super.close();
    }

}
