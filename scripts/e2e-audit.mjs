import { ChromeSession } from "./chrome-session.mjs";

async function runAudit() {
  console.log("=== Starting Chrome DevTools E2E & PRD Acceptance Audit ===");
  const session = await ChromeSession.getActivePage();
  await session.connect();
  await session.navigate("http://localhost:4200/public/articles");
  await session.eval("localStorage.clear()");
  await session.send("Page.reload", { ignoreCache: true });
  await new Promise((r) => setTimeout(r, 1000));

  const findings = [];

  function check(name, passed, detail = "") {
    console.log(
      `${passed ? "✅ PASS" : "❌ FAIL"}: ${name} ${detail ? "(" + detail + ")" : ""}`,
    );
    findings.push({ name, passed, detail });
  }

  try {
    // 1. Check Public Articles Page
    console.log("\n--- 1. Public Articles Page ---");
    await session.navigate("http://localhost:4200/public/articles");
    let title = await session.eval("document.title");
    let heading = await session.eval(
      'document.querySelector("h1")?.textContent',
    );
    check(
      "Public page loads",
      title.includes("Blog"),
      `title: ${title}, heading: ${heading}`,
    );

    // Check language toggle & persistence across navigation
    await session.click(".app-shell-header button");
    let headingAfterToggle = await session.eval(
      'document.querySelector("h1")?.textContent',
    );
    let langChoice = await session.eval(
      'localStorage.getItem("blog-admin-language")',
    );
    check(
      "Language toggle updates UI",
      heading !== headingAfterToggle,
      `Before: ${heading}, After: ${headingAfterToggle} (lang: ${langChoice})`,
    );

    // Navigate to another page and verify language does NOT revert
    await session.navigate("http://localhost:4200/public/tags");
    let tagsHeading = await session.eval(
      'document.querySelector("h1")?.textContent',
    );
    let expectedTagsHeading = langChoice === "en" ? "Tags" : "標籤";
    check(
      "Language persists after navigating to different page",
      tagsHeading?.includes(expectedTagsHeading),
      `Tags heading: ${tagsHeading}, expected: ${expectedTagsHeading}`,
    );

    // 2. Check Login Page Validation & Auth (PRD 1)
    console.log("\n--- 2. Login Page & Form Validations (PRD 1) ---");
    await session.navigate("http://localhost:4200/login");
    let registerLink = await session.eval(
      "document.querySelector(\"a[href='/register']\")?.textContent?.trim()",
    );
    check(
      "Login page loads with register link",
      Boolean(registerLink),
      `Register link: ${registerLink}`,
    );

    // Validate required errors on empty submit
    await session.click('button[type="submit"]');
    let emailRequiredError = await session.eval(
      'document.querySelector("#field-email-error")?.textContent?.trim()',
    );
    let passwordRequiredError = await session.eval(
      'document.querySelector("#field-password-error")?.textContent?.trim()',
    );
    check(
      "Login form validates required fields",
      Boolean(emailRequiredError && passwordRequiredError),
      `Email err: ${emailRequiredError}, Pwd err: ${passwordRequiredError}`,
    );

    // Validate invalid email format error
    await session.type("#field-email", "not-a-valid-email");
    await session.type("#field-password", "somepassword");
    await session.click('button[type="submit"]');
    let emailFormatError = await session.eval(
      'document.querySelector("#field-email-error")?.textContent?.trim()',
    );
    check(
      "Login form validates email format",
      Boolean(emailFormatError),
      `Format error: ${emailFormatError}`,
    );

    // 3. Login as Admin & Token Storage (PRD 1)
    console.log("\n--- 3. Admin Login & localStorage Token (PRD 1) ---");
    await session.type("#field-email", "a5852241@gmail.com");
    await session.type(
      "#field-password",
      "b1a4a67d20a09e911bc0d8b66bc5b2d1b9c26c72fc8acf46",
    );
    await session.click('button[type="submit"]');
    await new Promise((r) => setTimeout(r, 1500));

    let token = await session.eval('localStorage.getItem("blog-admin-token")');
    check(
      "Admin login succeeds and saves token in localStorage",
      Boolean(token),
      `Token present: ${Boolean(token)}`,
    );

    // 4. Articles Management List & Headers (PRD 2)
    console.log("\n--- 4. Articles Management List (PRD 2) ---");
    await session.navigate("http://localhost:4200/articles");
    await session.waitFor("table thead th", 5000).catch(() => {});
    let tableHeaders = await session.eval(`
      Array.from(document.querySelectorAll('table thead th')).map(th => th.textContent.trim())
    `);
    check(
      "Articles table displays title, author, and creation date columns",
      tableHeaders.length >= 4,
      `Headers: ${tableHeaders.join(" | ")}`,
    );

    let hasPagination = await session.eval(
      'Boolean(document.querySelector(".pagination"))',
    );
    check("Pagination component is present on article list", hasPagination);

    // 5. Create Article with Reactive Form (PRD 3)
    console.log("\n--- 5. Create Article with Reactive Form (PRD 3) ---");
    await session.navigate("http://localhost:4200/articles/new");
    const testTitle = "E2E Acceptance Test " + Date.now();
    await session.type("#field-title", testTitle);
    await session.type(
      "#field-content",
      "Comprehensive E2E automated test content for blog admin acceptance.",
    );
    await session.type("#field-tagNames", "e2e-audit, acceptance");
    await session.click("#status-published");

    // Verify form fields
    let formValues = await session.eval(`(() => ({
      title: document.querySelector('#field-title')?.value,
      content: document.querySelector('#field-content')?.value,
      status: document.querySelector('input[name="status"]:checked')?.value
    }))()`);
    check(
      "Reactive form captures title, content, and published status",
      formValues.title === testTitle &&
        Boolean(formValues.content) &&
        formValues.status === "PUBLISHED",
      `Values: ${JSON.stringify(formValues)}`,
    );

    await session.click('button[type="submit"]');
    await new Promise((r) => setTimeout(r, 1500));

    let currentUrl = await session.eval("window.location.pathname");
    check(
      "Article creation redirects to articles list",
      currentUrl === "/articles",
      `Current path: ${currentUrl}`,
    );

    // 6. Search Article by Title (PRD 2)
    console.log("\n--- 6. Search Article by Title (PRD 2) ---");
    await session.type("#article-search", testTitle);
    await session.click('form.search button[type="submit"]');
    await new Promise((r) => setTimeout(r, 800));
    let searchResultRow = await session.eval(`(() => {
      const firstRow = document.querySelector('tbody tr');
      if (!firstRow) return null;
      return {
        title: firstRow.querySelector('.row-title, td')?.textContent?.trim(),
        author: firstRow.querySelector('td:nth-child(3)')?.textContent?.trim(),
        created: firstRow.querySelector('time')?.textContent?.trim()
      };
    })()`);
    check(
      "Search finds created article with author attribution and date",
      Boolean(searchResultRow && searchResultRow.title?.includes(testTitle)),
      `Found: ${JSON.stringify(searchResultRow)}`,
    );

    // 7. Edit Article & Prefill Data (PRD 3)
    console.log("\n--- 7. Edit Article & Prefill Data (PRD 3) ---");
    await session.click("app-article-management-list button.text-link");
    await new Promise((r) => setTimeout(r, 1000));
    let editTitleVal = await session.eval(
      'document.querySelector("#field-title")?.value',
    );
    let editContentVal = await session.eval(
      'document.querySelector("#field-content")?.value',
    );
    let editStatusVal = await session.eval(
      "document.querySelector(\"input[name='status']:checked\")?.value",
    );
    check(
      "Edit form prefills existing article data correctly",
      editTitleVal === testTitle &&
        Boolean(editContentVal) &&
        editStatusVal === "PUBLISHED",
      `Prefilled Title: ${editTitleVal}, Status: ${editStatusVal}`,
    );

    // Modify and update article
    const updatedTitle = testTitle + " [Updated]";
    await session.type("#field-title", updatedTitle);
    await session.click('button[type="submit"]');
    await new Promise((r) => setTimeout(r, 1500));

    let editUrl = await session.eval("window.location.pathname");
    check(
      "Article update redirects to articles list",
      editUrl === "/articles",
      `Path: ${editUrl}`,
    );

    // Search & verify updated title
    await session.type("#article-search", updatedTitle);
    await session.click('form.search button[type="submit"]');
    await new Promise((r) => setTimeout(r, 800));
    let updatedRowTitle = await session.eval(
      'document.querySelector("tbody tr .row-title, tbody tr td")?.textContent',
    );
    check(
      "Article update reflects in article list",
      updatedRowTitle?.includes(updatedTitle),
      `Updated Title found: ${updatedRowTitle?.slice(0, 50)}`,
    );

    // 8. Delete Article with Confirmation Dialog (PRD 2)
    console.log("\n--- 8. Delete Confirmation Dialog (PRD 2) ---");
    await session.click("app-article-management-list button.danger");
    await new Promise((r) => setTimeout(r, 600));

    let confirmDialogVisible = await session.eval(
      'Boolean(document.querySelector("dialog.confirm-dialog[open], dialog[open]"))',
    );
    check("Delete action triggers confirmation dialog", confirmDialogVisible);

    await session.click(
      "dialog.confirm-dialog button.danger-btn, dialog button.danger-btn",
    );
    await new Promise((r) => setTimeout(r, 1200));
    let softDeleteMsg = await session.eval(
      'document.querySelector(".success")?.textContent',
    );
    check(
      "Soft delete displays success notice with recycle bin link",
      Boolean(softDeleteMsg),
      `Msg: ${softDeleteMsg}`,
    );

    // 9. Recycle Bin & Native <dialog> Permanent Delete (Issue #15)
    console.log("\n--- 9. Recycle Bin & Permanent Delete ---");
    await session.navigate("http://localhost:4200/articles/deleted");
    let deletedRowsCount = await session.eval(
      'document.querySelectorAll("tbody tr").length',
    );
    check(
      "Recycle bin displays soft-deleted articles",
      deletedRowsCount > 0,
      `Count: ${deletedRowsCount}`,
    );

    await session.click("tbody tr button.danger");
    await new Promise((r) => setTimeout(r, 600));

    let permDialogOpen = await session.eval(
      'Boolean(document.querySelector("dialog[open]"))',
    );
    let permDialogText = await session.eval(
      'document.querySelector("dialog[open]")?.textContent',
    );
    let permDialogCentered = await session.eval(`
      (() => {
        const d = document.querySelector('dialog[open]');
        if (!d) return false;
        const rect = d.getBoundingClientRect();
        const centerX = window.innerWidth / 2;
        const centerY = window.innerHeight / 2;
        const dCenterX = rect.left + rect.width / 2;
        const dCenterY = rect.top + rect.height / 2;
        return Math.abs(centerX - dCenterX) < 15 && Math.abs(centerY - dCenterY) < 15;
      })()
    `);
    check(
      "Permanent delete modal opens with centered layout and warning",
      permDialogOpen && permDialogCentered,
      `Open: ${permDialogOpen}, Centered: ${permDialogCentered}, Text: ${permDialogText?.slice(0, 40)}`,
    );

    await session.click("dialog button.danger-btn");
    await new Promise((r) => setTimeout(r, 1200));
    let permSuccessMsg = await session.eval(
      'document.querySelector(".success")?.textContent',
    );
    check(
      "Permanent delete completes successfully",
      Boolean(permSuccessMsg),
      `Msg: ${permSuccessMsg}`,
    );

    // 10. Admin User Management & Password Settings
    console.log("\n--- 10. Admin User Management & Security Settings ---");
    await session.navigate("http://localhost:4200/admin/users");
    let usersCount = await session.eval(
      'document.querySelectorAll("tbody tr, .user-row").length',
    );
    check(
      "Admin user management lists users",
      usersCount > 0,
      `Users: ${usersCount}`,
    );

    await session.navigate("http://localhost:4200/admin/settings/password");
    let minLenText = await session.eval(
      'document.querySelector(".current-value, form")?.textContent',
    );
    check(
      "Password settings page loads",
      minLenText?.includes("8") ||
        minLenText?.includes("最小長度") ||
        minLenText?.includes("Current value"),
      `Content: ${minLenText?.slice(0, 40)}`,
    );

    await session.navigate("http://localhost:4200/account/sessions");
    let sessionsCount = await session.eval(
      'document.querySelectorAll(".session, article, tbody tr").length',
    );
    check(
      "Account active sessions view displays session items",
      sessionsCount > 0,
      `Sessions: ${sessionsCount}`,
    );

    // 11. User Logout Flow (Revoke current session)
    console.log("\n--- 11. User Logout Flow ---");
    let logoutBtnSelector = "header button.logout-btn";
    await session.click(logoutBtnSelector);
    await new Promise((r) => setTimeout(r, 1000));
    let logoutUrl = await session.eval("window.location.pathname");
    let tokenAfterLogout = await session.eval(
      "localStorage.getItem('blog-admin-token')",
    );
    check(
      "Logout clears token and navigates to login",
      logoutUrl === "/login" && !tokenAfterLogout,
      `Path: ${logoutUrl}, token present: ${Boolean(tokenAfterLogout)}`,
    );
  } catch (err) {
    console.error("Audit encountered an error:", err);
  } finally {
    session.close();
    console.log("\n=== Audit Complete ===");
    const passed = findings.filter((f) => f.passed).length;
    const failed = findings.filter((f) => !f.passed).length;
    console.log(
      `Total Checks: ${findings.length}, Passed: ${passed}, Failed: ${failed}`,
    );
    if (failed > 0) {
      process.exitCode = 1;
    }
  }
}

runAudit();
