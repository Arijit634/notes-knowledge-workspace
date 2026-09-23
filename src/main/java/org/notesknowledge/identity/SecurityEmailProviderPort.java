package org.notesknowledge.identity;

/** Only an explicitly configured adapter may reach a real email provider. */
interface SecurityEmailProviderPort {
    void submit(String recipient, SecurityEmailMessageRenderer.Message message);
}
