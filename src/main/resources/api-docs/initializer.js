// Shows the read OpenAPI document served next to this page. A separate file rather than an inline
// script, because the page's Content-Security-Policy allows only scripts from this origin.
window.addEventListener("load", function () {
  window.ui = SwaggerUIBundle({
    url: "/docs/openapi.yaml",
    dom_id: "#swagger-ui",
    deepLinking: true,
    validatorUrl: null,
  });
});
