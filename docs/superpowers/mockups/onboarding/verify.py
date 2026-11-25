from argparse import ArgumentParser
from pathlib import Path
from tempfile import mkdtemp

from playwright.sync_api import expect, sync_playwright


TOUR = {
    "Carnet": "Scan now.",
    "Practice Lab": "One receipt.",
    "Field Guide": "Your scanner,",
}


def verify(browser, port, out):
    errors, external = [], []
    origin = f"http://localhost:{port}"
    url = origin + "/onboarding/"
    page = browser.new_page(viewport={"width": 1150, "height": 783}, reduced_motion="reduce")
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda e: errors.append(e.text) if e.type in {"error", "warning"} else None)
    page.on("request", lambda r: external.append(r.url) if not r.url.startswith((origin + "/", "data:")) else None)
    page.goto(url)
    shell = page.locator("#device")
    screen = page.locator("#screen")
    modal = page.get_by_role("dialog")

    def title(text):
        expect(page.locator("#screen-title")).to_contain_text(text)

    def chapter(name):
        page.locator("#chapters").get_by_role("button", name=name).click()

    def click(name, scope=shell, exact=True):
        scope.get_by_role("button", name=name, exact=exact).click()

    def choose(concept):
        click(concept, page.locator("#concepts"), exact=False)
        expect(page.locator("#concepts").get_by_role("button", name=concept)).to_have_attribute("aria-pressed", "true")

    def shot(name):
        page.screenshot(path=str(out / (name + ".png")), animations="disabled")

    expect(page).to_have_title("PristinePage — Three onboarding concepts")
    for concept, heading in TOUR.items():
        choose(concept)
        title(heading)
        shot(concept.lower().replace(" ", "-") + "-desktop")
        for name, expected in (
            ("Capture or import", "Collect"),
            ("Correction toolbox", "Try the correction toolbox"),
            ("Find a scan", "Find the page, not just the album"),
            ("Camera access", "Use the camera?"),
            ("Languages", "Choose your languages"),
            ("Library storage", "Your scans, your folder"),
            ("Ready to scan", "Ready when you are."),
        ):
            chapter(name)
            title(expected)
        chapter("Welcome")

    choose("Practice Lab")
    click("Capture sample")
    title("Review your pages")
    click("Add a page")
    expect(screen.get_by_text("2 pages in review")).to_be_visible()
    click("Finish importing")
    title("Save with suggested settings")
    click("Save album")
    title("Sample album saved")
    click("Continue")
    click("Frame")
    click("Adjust sample frame")
    expect(screen.get_by_role("button", name="Adjust sample frame")).to_have_attribute("aria-pressed", "true")
    click("Rotation")
    click("Turn right")
    assert "rotate(90deg)" in screen.locator(".sample-sheet").get_attribute("style")
    click("Correction")
    click("Grayscale")
    click("Apply")
    expect(screen.get_by_text("Edit applied to the sample")).to_be_visible()
    click("Original")
    expect(screen.get_by_text("Original · unchanged")).to_be_visible()
    assert "rotate(-13deg)" in screen.locator(".sample-sheet").get_attribute("style")
    click("Correction")
    expect(screen.get_by_text("Corrected · Grayscale")).to_be_visible()
    choose("Carnet")
    title("Try the correction toolbox")
    expect(screen.get_by_text("Corrected · Grayscale")).to_be_visible()
    shot("carnet-toolbox")
    choose("Practice Lab")
    click("Continue")
    query = screen.get_by_role("searchbox")
    query.fill("<unmatched>")
    expect(screen.get_by_text("No sample scans match.", exact=False)).to_be_visible()
    query.fill("olive oil")
    expect(screen.locator("mark")).to_have_text("Olive oil")
    click("Weekend papers · page 1", exact=False)
    title("Receipt · page 1")
    chapter("What stays yours")
    expect(screen.get_by_text("Work offline", exact=True)).to_be_visible()

    chapter("Camera access")
    click("Allow camera access")
    expect(modal).to_be_visible()
    click("Do not allow", modal)
    expect(screen.get_by_text("Not allowed", exact=True)).to_be_visible()
    click("Continue without camera")
    title("Choose your languages")
    screen.get_by_label("App language").select_option("French")
    screen.get_by_label("Japanese").check()
    expect(screen.get_by_text("3 selected")).to_be_visible()
    click("Continue")

    click("Import library")
    click("Documents / PristinePage Backup", modal, exact=False)
    expect(modal).to_contain_text("Default app storage")
    click("Copy and merge", modal)
    expect(screen.get_by_text("The source backup was not changed.", exact=False)).to_be_visible()
    expect(screen.locator(".callout").first).to_contain_text("Default app storage")
    click("Change library folder")
    click("Downloads / Other scanner", modal, exact=False)
    expect(modal).to_contain_text("Not a PristinePage library")
    page.keyboard.press("Escape")
    expect(modal).not_to_be_visible()
    expect(screen.get_by_role("button", name="Change library folder")).to_be_focused()
    click("Change library folder")
    click("Documents / My scans", modal, exact=False)
    click("Move library", modal, exact=False)
    expect(modal).to_contain_text("only removed after the destination is verified")
    click("Cancel", modal)
    expect(screen.locator(".callout").first).to_contain_text("Default app storage")
    click("Change library folder")
    click("Documents / My scans", modal, exact=False)
    click("Move library", modal, exact=False)
    click("Move library", modal)
    expect(screen.locator(".callout").first).to_contain_text("Documents / My scans")
    click("Change library folder")
    click("Documents / Travel archive", modal, exact=False)
    click("Move and merge", modal, exact=False)
    expect(modal).to_contain_text("From Documents / My scans to Documents / Travel archive")
    click("Cancel", modal)
    click("Change library folder")
    click("Documents / Travel archive", modal, exact=False)
    click("Open this library", modal, exact=False)
    expect(screen.get_by_text("Documents / My scans was left untouched.", exact=False)).to_be_visible()
    click("Change library folder")
    click("Documents / My scans", modal, exact=False)
    click("Start an empty library", modal, exact=False)
    expect(screen.get_by_text("Documents / Travel archive was left untouched.", exact=False)).to_be_visible()
    click("Export library")
    click("Documents / Backups", modal, exact=False)
    click("Export backup", modal)
    expect(screen.get_by_text("The active library is still Documents / My scans.", exact=False)).to_be_visible()
    screen.get_by_text("Test losing folder access", exact=True).click()
    click("Simulate access loss")
    expect(screen.get_by_text("The app will not switch to default storage.", exact=False)).to_be_visible()
    click("Reconnect library")
    click("Documents / Travel archive", modal, exact=False)
    expect(modal).to_contain_text("This is a different library")
    click("Choose again", modal)
    click("Documents / My scans", modal, exact=False)
    click("Reconnect", modal)
    expect(screen.get_by_text("Reconnected to Documents / My scans.", exact=False)).to_be_visible()
    click("Continue")
    click("Open My scans")
    title("My scans")
    page.reload()
    title("My scans")
    click("Settings")
    click("Replay onboarding", exact=False)
    title("Scan now.")
    chapter("Languages")
    expect(screen.get_by_label("App language")).to_have_value("French")
    expect(screen.get_by_label("Japanese")).to_be_checked()
    click("Skip tour")
    title("Settings")
    expect(screen.get_by_text("Not allowed", exact=True)).to_be_visible()
    assert not errors, errors
    assert not external, external

    for width in (320, 390):
        mobile = browser.new_page(viewport={"width": width, "height": 800}, is_mobile=True, has_touch=True, reduced_motion="reduce")
        mobile.goto(url)
        for concept in TOUR:
            mobile.locator("#concepts").get_by_role("button", name=concept).click()
            assert mobile.evaluate("document.documentElement.scrollWidth === innerWidth"), (width, concept)
        mobile.get_by_role("button", name="Moving from another app?", exact=False).click()
        assert mobile.evaluate("document.querySelector('#screen').scrollWidth <= document.querySelector('#screen').clientWidth"), width
        mobile.get_by_role("button", name="Change library folder").click()
        expect(mobile.get_by_role("dialog")).to_be_visible()
        mobile.get_by_role("button", name="Documents / Travel archive", exact=False).click()
        expect(mobile.get_by_role("dialog")).to_contain_text("A library already exists here")
        if width == 390:
            mobile.screenshot(path=str(out / "field-guide-folder-mobile.png"), animations="disabled")
        mobile.close()
    print("PASS: 3 complete tours, sample capture/edit/search, camera refusal, language persistence, library paths and reconnect, first-launch/replay, no runtime errors or external requests, 1150/390/320px.")
    print(f"Screenshots: {out}")


if __name__ == "__main__":
    parser = ArgumentParser(description="Verify the local onboarding mockup in Chromium.")
    parser.add_argument("--port", type=int, default=8768)
    port = parser.parse_args().port
    out = Path(mkdtemp(prefix="pristinepage-onboarding-qa-"))
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        try:
            verify(browser, port, out)
        finally:
            browser.close()
