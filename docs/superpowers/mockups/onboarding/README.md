# PristinePage onboarding concepts

Three interactive design alternatives for review, not an approved Android onboarding design: **Carnet** leads with the original and corrected result; **Practice Lab** starts with a sample receipt; **Field Guide** offers a skimmable checklist with direct entry into the capture and library paths.

Open [index.html](index.html) directly in a browser. It has no external assets, requests, or permissions. Try switching designs mid-tour, editing the sample receipt and viewing **Original**, then searching for **olive oil**. Complete or skip the tour and use **Settings → Replay onboarding** to see how it opens without resetting preferences. On desktop, **Reset demo to first launch** clears only this page's simulated state.

Camera access, capture, text recognition, app localization and folder operations are simulations. The receipt is hand-drawn synthetic vector art. The folder examples describe the target design, not shipping availability, cross-device rendering guarantees, or native synchronization. A rendered PDF is not an editable library backup. The mockup does not interact with Android, user documents, accounts or networks.

For local browser validation, run `python serve.py` from this directory and open `http://localhost:8768/onboarding/`. With Python Playwright and Microsoft Edge already installed, run `python -B verify.py` from another terminal. The smoke check tours all three designs, exercises editing/search, refusal, language persistence, folder operations/reconnection, and relaunch, and writes screenshots to its reported temporary directory.
