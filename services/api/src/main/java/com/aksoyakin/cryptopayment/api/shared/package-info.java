/**
 * Shared kernel: minimal, stable types shared across contexts (e.g. identifiers, money, the domain error base type).
 * <p>
 * Declared open so that the other contexts may depend on {@code shared.domain} directly. A shared kernel whose
 * types could only be reached through a facade would be a layer, not a kernel; keeping it open is only safe
 * because it stays small and framework-free.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.aksoyakin.cryptopayment.api.shared;
