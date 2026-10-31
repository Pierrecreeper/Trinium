// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

/** Metadata for the exact vanilla callback; never inferred from draw order. */
public interface DirectTerrainTag {
    void trinium$section(int section);
    int trinium$section();
}
