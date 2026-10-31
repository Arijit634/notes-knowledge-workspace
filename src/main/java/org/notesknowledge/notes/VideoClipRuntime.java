package org.notesknowledge.notes;

import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoChild;

/** Notes-owned byte transformation; never authority to send content to a provider. */
interface VideoClipRuntime {
    VideoPlan plan(byte[] original);
    VideoChild render(byte[] original,VideoPlan plan,int first,int last);
    default org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioPlan audioPlan(byte[] original){throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    default org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioChild audio(byte[] original,org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioPlan plan){throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
