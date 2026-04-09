# OptimalX Debug Tracker

## 1. Three-dot menu placement

**Issue:**
The three-dot settings/menu button only appears on the main page.

**Expected:**
It should also appear on subfolder and note pages in a consistent location.

**Actual:**
Only visible on main page.

**Priority:** Medium

**Notes:** UI consistency issue.

---

## 2. Text editor menu text is invisible

**Issue:** Editor text and editor toolbar/menu labels are white and not readable.

**Expected:**
Proper contrast for readability.

**Actual:** Text blends into background. it should be black text might be a dark theme issue as the light theme has visible text that is black

**Priority:** Medium

**Notes:** Likely theme/color issue.

---

## 3. Note content not saving

**Issue:**
Typed content disappears after leaving and reopening the note.

**Expected:**
Content persists after reopening.

**Actual:**
Only persists in temporary state (swipe back works, reopen does not).

**Priority:** High

**Notes:** Likely not committing to storage/database.

---

## 4. File viewer back navigation incorrect

**Issue:**
Back button navigates to previous files/images instead of exiting to files page.

**Expected:** androids back button returns to files page when coming from a panel made by a view file.  

**Actual:**
Back cycles through file/image history.

**Priority:** High

**Notes:** Navigation stack misconfigured. probably best to discuss this with the developer as the files loading as panels might not be the best option for viewing the files. 

---

## 5. File/image swipe behavior conflict

**Issue:**
Images move left/right and interfere with file-to-file navigation.

**Expected:**
Clear distinction between image movement and navigation.

**Actual:** Swipe behavior is inconsistent and awkward. the zoom in and out is great though.  a rotate button would be an excellent addition

**Priority:** Medium

**Notes:** Images likely treated as navigation panels. would be a good time to add a rotate image or .pdf (if that is a thing) button. 

---

## 6. Chat requires extra scroll before typing

**Issue:** User must scroll up before being able to type. text input is below bottom screen. 

**Expected:**
Input field immediately accessible.

**Actual:**
Initial layout is offset.

**Priority:** High

**Notes:** Layout/scroll positioning issue. the partial screen is great as the user can still see the text editor and folders. if possible this feature would work even better if the user navigate the text editor or folder pages while the Eidos chat pull up was enabled. if this is not possible just let the developer know. 

---

## 7. Chat input hidden by keyboard

**Issue:**
Text input does not move up when keyboard appears.

**Expected:**
Input remains visible above keyboard.

**Actual:**
Input is obscured.

**Priority:** High

**Notes:** Missing keyboard avoidance handling.

---

## 8. Chat input positioned too low

**Issue:**
Input sits below expected Android bottom inset.

**Expected:**
Aligned properly above system UI.

**Actual:**
Visually too low.

**Priority:** Medium

**Notes:** Likely padding/inset miscalculation.

---

## 9. AI provider unavailable error

**Issue:**
App shows provider unavailable despite API key being entered.

**Expected:**
Provider should function or give clear error.

**Actual:**
Generic error message.

**Priority:** High

**Notes:** Possibly settings not saved or provider not applied.

---

## 10. Settings lack save/apply feedback

**Issue:**
No confirmation that settings are saved.

**Expected:**
Clear save action or auto-save feedback.

**Actual:**
Unclear if settings persist.

**Priority:** High

**Notes:** UX issue affecting configuration reliability. I am not sure if this is necessary or not, if everything is working there would be no need, but as of now the grok api should be working and it is not, which lead to the concern of no confirmation, a toast when something is selected or an api key is added would be acceptable even if that would be a simpler process. 

---

## 11. API keys visible

**Issue:**
API keys are displayed in plain text.

**Expected:**
Keys masked with optional reveal toggle.

**Actual:**
Fully visible.

**Priority:** High

**Notes:** Security concern. could be nice to have a visible on/off toggle, that operated for all APIs at the same time, unless there is a security risk there as well then just mask them.  

---

## 12. Add API key help links

**Issue:**
No guidance for obtaining API keys.

**Expected:**
Links or help indicators for each provider.

**Actual:**
No assistance provided.

**Priority:** Low

**Notes:** UX enhancement.

---

## 13. Limited layout options

**Issue:**
Only list and basic grid views available.

**Expected:**
Additional grid density options (e.g., 4-column).

**Actual:**
Limited layout choices.

**Priority:** Low

**Notes:** UI enhancement.

---
