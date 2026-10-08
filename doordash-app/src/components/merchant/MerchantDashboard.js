import { Tabs } from "antd";
import ActiveOrders from "./ActiveOrders";
import OrderSearch from "./OrderSearch";

// 商家后台：商家账号登录后看到的页面
const MerchantDashboard = () => {
  return (
    <Tabs
      defaultActiveKey="active"
      items={[
        { key: "active", label: "Active orders", children: <ActiveOrders /> },
        { key: "search", label: "Order search", children: <OrderSearch /> },
      ]}
    />
  );
};

export default MerchantDashboard;
