package org.notesknowledge.notes;

import org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfChild;

/** Deployment seam for a qualified isolated PdfPageRasterWorker process, NOT a parser in Spring.
 * No default implementation is registered: a boolean configuration flag cannot qualify an OS
 * boundary. A runtime must enforce the approved network/filesystem/resource/process-tree and
 * scratch-scavenging contract before it can be provided. Explicit production selection performs
 * custody, pinned identity and startup checks. Synthetic test launchers are test-only.
 */
interface PdfPageRuntime {
    PdfChild render(byte[] original,int page);
}
