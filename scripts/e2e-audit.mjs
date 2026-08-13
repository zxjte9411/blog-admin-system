import { ChromeSession } from "./chrome-session.mjs";

async function runAudit() {
  console.log("=== Starting Chrome DevTools E2E & PRD Audit ===");
  const session = await ChromeSession.getActivePage();
  await session.connect();
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

    // Check language toggle
    let langBtn = await session.eval(
      'document.querySelector(".app-shell-header button")?.textContent?.trim()',
    );
    console.log("Current Lang Button:", langBtn);
    await session.click(".app-shell-header button");
    let headingAfterToggle = await session.eval(
      'document.querySelector("h1")?.textContent',
    );
    check(
      "Language toggle updates UI",
      heading !== headingAfterToggle,
      `Before: ${heading}, After: ${headingAfterToggle}`,
    );

    // 2. Check Login Page & Register Link (Issue #14 & PRD 1)
    console.log("\n--- 2. Login Page & Registration Link ---");
    await session.navigate("http://localhost:4200/login");
    let registerLink = await session.eval(
      "document.querySelector(\"a[href='/register']\")?.textContent?.trim()",
    );
    check(
      "Login page loads with register link",
      Boolean(registerLink),
      `Register link text: ${registerLink}`,
    );

    // Validate form required errors
    await session.click('button[type="submit"]');
    let emailError = await session.eval(
      'document.querySelector("#field-email-error")?.textContent?.trim()',
    );
    let passwordError = await session.eval(
      'document.querySelector("#field-password-error")?.textContent?.trim()',
    );
    check(
      "Validation on empty login submit",
      Boolean(emailError && passwordError),
      `Email err: ${emailError}, Pwd err: ${passwordError}`,
    );

    // 3. Login as Admin
    console.log("\n--- 3. Login as Admin ---");
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
      `Token exists: ${Boolean(token)}`,
    );

    // 4. Articles Management (PRD 2 & 3)
    console.log("\n--- 4. Articles Management List ---");
    await session.navigate("http://localhost:4200/articles");
    let newArticleBtn = await session.eval(
      "document.querySelector(\"a[href='/articles/new']\")?.textContent?.trim()",
    );
    check("Articles page loads for logged-in admin", Boolean(newArticleBtn));

    // 5. Create Article (Reactive Form, PRD 3)
    console.log("\n--- 5. Create New Article ---");
    await session.navigate("http://localhost:4200/articles/new");
    const testTitle = "E2E Test Article " + Date.now();
    await session.type("#field-title", testTitle);
    await session.type(
      "#field-content",
      "This is an automated E2E test article content.",
    );
    await session.type("#field-tagNames", "tech, e2e-testing");
    await session.click("#status-published");
    await session.click('button[type="submit"]');
    await new Promise((r) => setTimeout(r, 1500));

    let currentUrl = await session.eval("window.location.pathname");
    check(
      "Create article redirects to articles list",
      currentUrl === "/articles",
      `Current path: ${currentUrl}`,
    );

    // Verify created article in list & search
    await session.type("#article-search", testTitle);
    await session.click('form.search button[type="submit"]');
    await new Promise((r) => setTimeout(r, 800));
    let firstArticleTitle = await session.eval(
      'document.querySelector("tbody tr td")?.textContent',
    );
    check(
      "Search finds newly created article",
      firstArticleTitle?.includes(testTitle),
      `Found text: ${firstArticleTitle?.slice(0, 50)}`,
    );

    // 6. Edit Article (Prefilled data, PRD 3)
    console.log("\n--- 6. Edit Article ---");
    await session.click("app-article-management-list button.text-link");
    await new Promise((r) => setTimeout(r, 1000));
    let editTitleVal = await session.eval(
      'document.querySelector("#field-title")?.value',
    );
    check(
      "Edit form prefills article data",
      editTitleVal === testTitle,
      `Prefilled title: ${editTitleVal}`,
    );

    // 7. Soft Delete & Restore & Permanent Delete (Issue #15)
    console.log("\n--- 7. Soft Delete & Trash Management ---");
    await session.navigate("http://localhost:4200/articles");
    await session.eval(`
      (() => {
        window.confirm = () => true;
        const delBtn = document.querySelector('app-article-management-list button.danger');
        delBtn?.click();
      })()
    `);
    await new Promise((r) => setTimeout(r, 1200));
    let successMsg = await session.eval(
      'document.querySelector(".success")?.textContent',
    );
    check(
      "Soft delete displays success notification",
      Boolean(successMsg),
      `Msg: ${successMsg}`,
    );

    // Navigate to Deleted Articles
    await session.navigate("http://localhost:4200/articles/deleted");
    let permanentDeleteBtn = await session.eval(
      'document.querySelector("tbody button.danger")?.textContent?.trim()',
    );
    check(
      "Trash displays deleted article with restore & permanent delete buttons",
      Boolean(permanentDeleteBtn),
      `Buttons available: ${permanentDeleteBtn}`,
    );

    // Test Native <dialog> Permanent Delete
    console.log("\n--- 8. Native <dialog> Confirmation & Permanent Delete ---");
    await session.click("tbody button.danger");
    await new Promise((r) => setTimeout(r, 600));

    let dialogOpen = await session.eval(
      'Boolean(document.querySelector("dialog[open]"))',
    );
    let dialogText = await session.eval(
      'document.querySelector("dialog[open]")?.textContent',
    );
    let dialogCentered = await session.eval(`
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
      "Permanent delete dialog opens with clear irreversible warning",
      dialogOpen &&
        Boolean(
          dialogText?.includes("無法復原") || dialogText?.includes("undone"),
        ),
      `Dialog text: ${dialogText?.slice(0, 60)}`,
    );
    check(
      "Permanent delete dialog is centered on screen",
      dialogCentered,
      `Centered: ${dialogCentered}`,
    );

    // Confirm permanent delete
    await session.click("dialog button.danger-btn");
    await new Promise((r) => setTimeout(r, 1200));
    let permSuccessMsg = await session.eval(
      'document.querySelector(".success")?.textContent',
    );
    check(
      "Permanent delete executes and displays success message",
      Boolean(permSuccessMsg),
      `Success msg: ${permSuccessMsg}`,
    );

    // 8. Admin User Management & Password Settings
    console.log("\n--- 9. Admin User Management & Settings ---");
    await session.navigate("http://localhost:4200/admin/users");
    let usersCount = await session.eval(
      'document.querySelectorAll("tbody tr, .user-row").length',
    );
    check(
      "Admin user management list loads users",
      usersCount > 0,
      `Users count: ${usersCount}`,
    );

    await session.navigate("http://localhost:4200/admin/settings/password");
    let minLenText = await session.eval(
      'document.querySelector(".current-value, form")?.textContent',
    );
    check(
      "Password setting page loads current minimum length",
      minLenText?.includes("8") ||
        minLenText?.includes("最小長度") ||
        minLenText?.includes("Current value"),
      `Content: ${minLenText?.slice(0, 40)}`,
    );

    // 9. Account Sessions
    console.log("\n--- 10. Account Sessions & Security ---");
    await session.navigate("http://localhost:4200/account/sessions");
    let sessionItems = await session.eval(
      'document.querySelectorAll(".session, article").length',
    );
    check(
      "Account sessions view lists active sessions",
      sessionItems > 0,
      `Active sessions: ${sessionItems}`,
    );
  } catch (err) {
    console.error("Audit encountered an error:", err);
  } finally {
    session.close();
    console.log("\n=== Audit Complete ===");
    console.log(
      `Total Checks: ${findings.length}, Passed: ${findings.filter((f) => f.passed).length}, Failed: ${findings.filter((f) => !f.passed).length}`,
    );
  }
}

runAudit();
