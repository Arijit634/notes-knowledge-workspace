package org.notesknowledge.notes;

import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoChild;

/** Notes-owned byte transformation; never authority to send content to a provider. */
interface VideoClipRuntime {
    VideoPlan plan(byte[] original);
    VideoChild render(byte[] original,VideoPlan plan,int first,int last);
}
