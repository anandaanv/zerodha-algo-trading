# Legal Pages Setup

## Overview

Privacy Policy and Terms of Service pages have been created for Google OAuth verification and general compliance.

---

## Files Created

### Documentation (Markdown):
- ✅ `docs/PRIVACY_POLICY.md` - Comprehensive privacy policy
- ✅ `docs/TERMS_OF_SERVICE.md` - Comprehensive terms of service

### Public HTML Pages:
- ✅ `ui/chart-draw-app/public/privacy-policy.html`
- ✅ `ui/chart-draw-app/public/terms-of-service.html`

### Backend:
- ✅ `LegalController.java` - Serves legal pages
- ✅ `SecurityConfig.java` - Updated to allow public access to legal pages

---

## Access URLs

Once deployed, these pages will be accessible at:

### Development:
- http://localhost:8080/privacy-policy
- http://localhost:8080/privacy-policy.html
- http://localhost:8080/privacy
- http://localhost:8080/terms-of-service
- http://localhost:8080/terms-of-service.html
- http://localhost:8080/terms

### Production:
- https://yourdomain.com/privacy-policy
- https://yourdomain.com/terms-of-service

---

## For Google OAuth Verification

When submitting your app to Google for OAuth verification, you'll need to provide:

### 1. Privacy Policy URL
```
https://yourdomain.com/privacy-policy
```

### 2. Terms of Service URL
```
https://yourdomain.com/terms-of-service
```

### 3. Application Homepage
```
https://yourdomain.com
```

---

## What's Included

### Privacy Policy Covers:
- ✅ Information collection (account, OAuth, trading data)
- ✅ How we use information
- ✅ How we share information (we DON'T sell data)
- ✅ Data security measures
- ✅ User rights (access, deletion, portability)
- ✅ Cookies and tracking
- ✅ Third-party services (Zerodha, Google)
- ✅ GDPR and CCPA compliance
- ✅ Children's privacy (18+ only)
- ✅ Contact information

### Terms of Service Covers:
- ✅ Eligibility (18+ years old)
- ✅ Account registration and security
- ✅ Acceptable use policy
- ✅ Prohibited activities
- ✅ Service features and limitations
- ✅ Intellectual property rights
- ✅ Disclaimer of warranties
- ✅ Limitation of liability
- ✅ Termination policy
- ✅ Dispute resolution
- ✅ Trading risk disclaimer

---

## Customization Required

Before going live, you **MUST** update the following placeholders:

### In Both Documents:

1. **Contact Information:**
   ```
   Email: [your-email@example.com]
   Address: [Your Company Address]
   Support: [Your Support URL]
   ```

2. **Company Name:**
   Replace "Trading Platform" with your actual product/company name

3. **Jurisdiction:**
   ```
   Governing Law: [Your Jurisdiction]
   ```

4. **Data Protection Officer (if required):**
   ```
   DPO Email: [dpo@example.com]
   DPO Address: [DPO Address]
   ```

5. **Effective Date:**
   Update "March 19, 2026" to your actual launch date

---

## HTML Pages Customization

The HTML pages include:
- Professional styling
- Responsive design (mobile-friendly)
- Clear sections with color-coded highlights
- Links to both legal pages
- Easy navigation

To customize branding:
1. Update color scheme in `<style>` section
2. Change `#667eea` (primary color) to your brand color
3. Add your logo/company name
4. Update footer links

---

## Testing

### 1. Start Backend:
```bash
./gradlew bootRun
```

### 2. Access Pages:
```
http://localhost:8080/privacy-policy
http://localhost:8080/terms-of-service
```

### 3. Verify:
- [ ] Pages load without authentication
- [ ] Content displays correctly
- [ ] Links work properly
- [ ] Mobile responsive
- [ ] No security warnings

---

## Google OAuth Submission Checklist

When submitting to Google:

- [ ] Privacy Policy URL is publicly accessible
- [ ] Terms of Service URL is publicly accessible
- [ ] Pages are served over HTTPS (in production)
- [ ] Contact information is accurate
- [ ] Clear explanation of data usage
- [ ] Explicit mention of Google OAuth integration
- [ ] GDPR/CCPA compliance sections present
- [ ] Pages are in English (or translated as needed)

---

## Legal Disclaimer

**IMPORTANT:** These documents are templates based on common SaaS practices. They should be reviewed and customized by a qualified attorney before use in production.

We recommend:
1. Consulting with a lawyer specializing in:
   - Technology law
   - Data privacy (GDPR/CCPA)
   - Securities regulation (for trading platforms)

2. Regular reviews and updates as:
   - Laws change
   - Your service evolves
   - New features are added

3. Consider additional policies if needed:
   - Cookie Policy (separate document)
   - Acceptable Use Policy (detailed version)
   - Content Policy
   - Refund Policy (if offering paid services)

---

## Compliance Notes

### GDPR (EU Users):
- ✅ Data collection transparency
- ✅ Lawful basis for processing
- ✅ User rights (access, deletion, portability)
- ✅ Data retention policies
- ✅ Third-party data sharing disclosure
- ⚠️ Consider appointing a DPO if required

### CCPA (California Users):
- ✅ Data collection disclosure
- ✅ "Do Not Sell" (we don't sell data)
- ✅ Deletion rights
- ✅ Non-discrimination clause

### Trading Platforms:
- ✅ Risk warnings
- ✅ "Not financial advice" disclaimer
- ✅ No profit guarantees
- ✅ User responsibility for trading decisions

---

## Maintenance

### Update Privacy Policy when:
- Adding new data collection
- Integrating new third-party services
- Changing data retention policies
- Updating security measures

### Update Terms of Service when:
- Adding new features
- Changing pricing (if applicable)
- Modifying user restrictions
- Updating termination policies

### Notification Process:
1. Update document with new "Last Updated" date
2. Send email to users (for material changes)
3. Show in-app notification
4. Require re-acceptance (for major changes)

---

## Additional Resources

### Legal Templates:
- [Termly](https://termly.io/) - Privacy policy generator
- [TermsFeed](https://www.termsfeed.com/) - Legal documents generator
- [iubenda](https://www.iubenda.com/) - Compliance solutions

### Compliance Tools:
- [OneTrust](https://www.onetrust.com/) - Privacy management
- [TrustArc](https://www.trustarc.com/) - Privacy compliance

### Legal Review:
Consider hiring a lawyer through:
- [UpCounsel](https://www.upcounsel.com/)
- [LegalZoom](https://www.legalzoom.com/)
- Local technology law firms

---

## Support

If you need help customizing these documents:
1. Consult with a qualified attorney
2. Review examples from similar SaaS platforms
3. Consider compliance services like iubenda or Termly

---

**Legal Pages Setup Complete! ✅**

Remember to customize before going live and have them reviewed by legal counsel.
