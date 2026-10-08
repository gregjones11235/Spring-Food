export const login = (credentials) => {
  return fetch("/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: `username=${encodeURIComponent(credentials.username)}&password=${encodeURIComponent(credentials.password)}`,
  }).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Fail to log in");
    }
  });
};

export const signup = (data) => {
const signupUrl = "/signup";

return fetch(signupUrl, {
method: "POST",
headers: {
"Content-Type": "application/json",
},
body: JSON.stringify(data),
}).then((response) => {
if (response.status < 200 || response.status >= 300) {
throw Error("Fail to sign up");
}
});
};

export const getMenus = (restId) => {
return fetch(`/restaurant/${restId}/menu`).then((response) => {
if (response.status < 200 || response.status >= 300) {
throw Error("Fail to get menus");
}

return response.json();
});
};

// 店铺热销榜（Redis ZSet），按销量从高到低
export const getHotItems = (restId, n = 5) => {
  return fetch(`/restaurant/${restId}/hot-items?n=${n}`).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Fail to get popular items");
    }

    return response.json();
  });
};

export const searchRestaurants =({ keyword = "", page = 1, size = 12 }) => {
  const params = new URLSearchParams({ keyword, page, size });
  return fetch(`/restaurants?${params}`).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Fail to get restaurants");
    }

    return response.json();
  });
};

export const getCart = () => {
return fetch("/cart").then((response) => {
if (response.status < 200 || response.status >= 300) {
throw Error("Fail to get shopping cart data");
}

return response.json();
});
};

export const checkout = () => {
return fetch("/cart/checkout", {
method: "POST",
headers: {
"Content-Type": "application/json",
},
}).then((response) => {
if (response.status < 200 || response.status >= 300) {
throw Error("Fail to checkout");
}

return response.json();
});
};

export const addItemToCart = (itemId) => {
const payload = {
menu_id: itemId,
};

return fetch(`/cart`, {
method: "POST",
headers: {
"Content-Type": "application/json",
},
body: JSON.stringify(payload),
}).then((response) => {
if (response.status < 200 || response.status >= 300) {
throw Error("Fail to add menu item to shopping cart");
}
});
};

// 当前登录用户；未登录时后端返回 401，这里返回 null
export const getMe = () => {
  return fetch("/me").then((response) => {
    if (response.status === 401) {
      return null;
    }
    if (response.status < 200 || response.status >= 300) {
      throw Error("Fail to get current user");
    }

    return response.json();
  });
};

export const logout = () => {
  return fetch("/logout", { method: "POST" });
};

// ================= 商家后台 =================

// 状态已变化（409）时用单独的错误信息，页面据此提示并刷新列表
const merchantFetch = (url, options, errorMessage) => {
  return fetch(url, options).then((response) => {
    if (response.status === 409) {
      throw Error("Order status has changed, list refreshed");
    }
    if (response.status < 200 || response.status >= 300) {
      throw Error(errorMessage);
    }

    return response.status === 200 && response.headers.get("Content-Type")?.includes("json")
      ? response.json()
      : null;
  });
};

export const getActiveOrders = () => {
  return merchantFetch("/merchant/orders/active", {}, "Fail to get active orders");
};

// criteria: { date: "2026-09-01", phone, phoneMatch: "exact" | "prefix" | "suffix", dish, statuses: [] }
export const searchMerchantOrders = (criteria, page, size = 20) => {
  const params = new URLSearchParams({ page, size });
  if (criteria.date) params.append("date", criteria.date);
  if (criteria.phone) {
    params.append("phone", criteria.phone);
    params.append("phone_match", criteria.phoneMatch || "exact");
  }
  if (criteria.dish) params.append("dish", criteria.dish);
  (criteria.statuses || []).forEach((s) => params.append("status", s));
  return merchantFetch(`/merchant/orders/search?${params}`, {}, "Fail to search orders");
};

// action: accept / reject / complete
export const updateOrderStatus = (orderId, action) => {
  return merchantFetch(`/merchant/orders/${orderId}/${action}`, { method: "POST" }, `Fail to ${action} order`);
};
