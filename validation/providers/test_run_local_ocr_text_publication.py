import unittest
from xml.etree import ElementTree as ET

from run_local_ocr_text_publication import confirmation_point, expected_output


OWNER = "com.android.documentsui"
FOLDER = "PristinePage-Export-000000111111"
DIGEST = "0" * 64
MARKER = (
    f"INSTRUMENTATION_STATUS: ocrTextExpectedSha={DIGEST}\n"
    "INSTRUMENTATION_STATUS: ocrTextExpectedBytes=36\n"
    "INSTRUMENTATION_STATUS_CODE: 68\n"
)
PICKER = f"""
<hierarchy>
  <node package="{OWNER}" resource-id="android:id/title" class="android.widget.TextView" text="{FOLDER}"/>
  <node package="{OWNER}" resource-id="android:id/title" class="android.widget.EditText" text="recognized-text.txt" enabled="true"/>
  <node package="{OWNER}" resource-id="android:id/button1" text="ENREGISTRER" enabled="true" clickable="true" bounds="[50,10][70,30]"/>
</hierarchy>
"""


class TextPublicationDriverTest(unittest.TestCase):
    def test_only_one_bounded_ready_checkpoint_can_authorize_inspection(self):
        self.assertEqual((36, DIGEST), expected_output(MARKER))
        self.assertIsNone(expected_output(MARKER.replace("68", "0")))
        for invalid in (MARKER * 2, MARKER.replace("36\n", "393217\n"), MARKER.replace("ExpectedBytes", "ExpectedSha")):
            with self.subTest(output=invalid), self.assertRaises(RuntimeError):
                expected_output(invalid)

    def test_confirmation_requires_the_exact_picker_folder_filename_and_button(self):
        self.assertEqual(("60", "20"), confirmation_point(ET.fromstring(PICKER), OWNER, FOLDER))
        self.assertIsNone(confirmation_point(ET.fromstring(PICKER), "example.other", FOLDER))
        for invalid in (
            PICKER.replace(FOLDER, "Downloads"),
            PICKER.replace("recognized-text.txt", "renamed.txt"),
            PICKER.replace('clickable="true"', 'clickable="false"'),
            PICKER.replace("[50,10][70,30]", "[50,10][50,30]"),
        ):
            with self.subTest(xml=invalid):
                self.assertIsNone(confirmation_point(ET.fromstring(invalid), OWNER, FOLDER))
