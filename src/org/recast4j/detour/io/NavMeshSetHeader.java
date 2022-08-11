/*
Recast4J Copyright (c) 2015-2018 Piotr Piastucki piotr@jtilia.org

This software is provided 'as-is', without any express or implied
warranty.  In no event will the authors be held liable for any damages
arising from the use of this software.
Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:
1. The origin of this software must not be misrepresented; you must not
 claim that you wrote the original software. If you use this software
 in a product, an acknowledgment in the product documentation would be
 appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be
 misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
*/
package org.recast4j.detour.io;

import org.recast4j.detour.NavMeshParams;

public class NavMeshSetHeader {

    static final int NAVMESHSET_MAGIC = 'M' << 24 | 'S' << 16 | 'E' << 8 | 'T';
    static final int NAVMESHSET_VERSION = 1;
    static final int NAVMESHSET_VERSION_RECAST4J_1 = 0x8801;
    static final int NAVMESHSET_VERSION_RECAST4J = 0x8802;

    int magic, version, numTiles, maxVerticesPerPoly;
    NavMeshParams params = new NavMeshParams();

}
