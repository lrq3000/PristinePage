"""Reversible radio isolation on an already claimed, serial-verified synthetic guest."""

import re
import subprocess
import time

from local_ocr_files_journey import LocalFilesJourney


def disconnected(output):
    # The observed API 28 dump delimits active agents between these two headings. Any agent or incomplete section makes the isolation proof inconclusive.
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    defaults = [line for line in lines if line.startswith("Active default network:")]
    if defaults != ["Active default network: none"] or lines.count("Current Networks:") != 1 or lines.count("Network Requests:") != 1:
        return False
    return lines.index("Network Requests:") == lines.index("Current Networks:") + 1


def wifi_disabled(output):
    return re.findall(r"(?m)^Wi-Fi is (enabled|disabled)[ \t\r]*$", output) == ["disabled"]


def isolated(state, connectivity, wifi):
    return (state.get("airplane_mode_on") == "1" and state.get("wifi_on") in ("0", "2")
            and state.get("mobile_data") == "0" and disconnected(connectivity) and wifi_disabled(wifi))


class AirplaneJourney:
    KEYS = ("airplane_mode_on", "wifi_on", "mobile_data")

    def __init__(self, run, target):
        self.run, self.target = run, target

    def shell(self, *args, timeout=15):
        return self.run.command([*self.target, "shell", *args], timeout=timeout).strip()

    def read(self, deadline=None):
        state = {}
        for key in self.KEYS:
            command = ("settings", "get", "global", key)
            timeout = 15 if deadline is None else min(15, deadline - time.monotonic())
            if timeout <= 0:
                raise subprocess.TimeoutExpired(command, timeout=0)
            state[key] = self.shell(*command, timeout=timeout)
        return state

    def observe(self, label):
        state = self.read()
        networks = self.shell("dumpsys", "connectivity")
        wifi = self.shell("dumpsys", "wifi")
        self.run.path(f"airplane-{label}-connectivity.txt").write_text(networks, encoding="utf-8")
        self.run.save(self.run.path(f"airplane-{label}.json"), {
            "settings": state, "noActiveNetwork": disconnected(networks),
            "wifiAdapterDisabled": wifi_disabled(wifi), "isolated": isolated(state, networks, wifi),
        }, durable=True)
        return state, isolated(state, networks, wifi)

    def await_state(self, validate, explanation):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if validate():
                return
            time.sleep(0.5)
        raise RuntimeError(explanation)

    def await_control_channel(self, seconds=40):
        # On this guest the airplane switch may interrupt the current local ADB stream. Recheck the
        # exact serial only; never reconnect broadly or repeat the last UI action while its outcome is unknown.
        deadline = time.monotonic() + seconds
        last = None
        while time.monotonic() < deadline:
            try:
                state = self.read(deadline)
                if all(value in ("0", "1", "2") for value in state.values()):
                    return state
            except (subprocess.TimeoutExpired, AssertionError) as error:
                last = error
            time.sleep(max(0, min(1, deadline - time.monotonic())))
        raise RuntimeError("The dedicated guest control channel did not recover; radio state must be reconciled after boot") from last

    def airplane(self, enabled):
        # Android owns the protected broadcast. Use its real Settings switch, not a direct global setting write.
        opened = self.shell("am", "start", "-W", "-a", "android.settings.AIRPLANE_MODE_SETTINGS",
                            "-p", "com.android.settings")
        if "Status: ok" not in opened:
            raise RuntimeError("Guest airplane-mode Settings did not open")
        settings = LocalFilesJourney(self.run, self.target, "com.android.settings", None)
        settings.tap(("Airplane mode", "Mode Avion", "Mode avion"))
        expected = "1" if enabled else "0"
        # The same guest previously needed the full bounded boot-read window to become responsive again.
        if self.await_control_channel(seconds=170)["airplane_mode_on"] != expected:
            raise RuntimeError("Airplane-mode switch did not reach its target")

    def restore(self, initial):
        if set(initial) != set(self.KEYS) or any(value not in ("0", "1") for value in initial.values()):
            raise RuntimeError("Only a verified original radio state may be restored")
        if self.await_control_channel()["airplane_mode_on"] != initial["airplane_mode_on"]:
            self.airplane(initial["airplane_mode_on"] == "1")
        for key, service in (("wifi_on", "wifi"), ("mobile_data", "data")):
            if self.read()[key] != initial[key]:
                self.shell("svc", service, "enable" if initial[key] == "1" else "disable")
        self.await_state(lambda: self.read() == initial, "Original guest radio settings could not be restored")
        if self.observe("restored")[0] != initial:
            raise RuntimeError("Guest radio settings changed during restoration readback")

    def is_offline(self):
        return isolated(self.read(), self.shell("dumpsys", "connectivity"), self.shell("dumpsys", "wifi"))

    def require_offline(self, label):
        _, verified = self.observe(label)
        if not verified:
            raise RuntimeError("OCR journey was not isolated from network transports")
