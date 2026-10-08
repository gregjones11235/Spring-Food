import { Button, Layout, Space, Spin, Typography } from "antd";
import { useEffect, useState } from "react";
import FoodList from "./components/FoodList";
import LoginForm from "./components/LoginForm";
import MyCart from "./components/MyCart";
import SignupForm from "./components/SignupForm";
import MerchantDashboard from "./components/merchant/MerchantDashboard";
import { getMe, logout } from "./utils";

const { Header, Content } = Layout;
const { Title, Text } = Typography;

function App() {
  // undefined：还在确认登录状态；null：未登录；对象：当前用户（/me 的返回）
  const [me, setMe] = useState(undefined);

  // 刷新页面时 session 还在，直接恢复登录状态
  useEffect(() => {
    getMe()
      .then(setMe)
      .catch(() => setMe(null));
  }, []);

  const onLoginSuccess = () => {
    getMe()
      .then(setMe)
      .catch(() => setMe(null));
  };

  const onLogout = () => {
    logout().finally(() => setMe(null));
  };

  const isMerchant = me?.roles?.includes("ROLE_MERCHANT");

  let headerRight;
  let content;
  if (me === undefined) {
    headerRight = null;
    content = <Spin />;
  } else if (me === null) {
    headerRight = <SignupForm />;
    content = <LoginForm onSuccess={onLoginSuccess} />;
  } else {
    headerRight = (
      <Space>
        {isMerchant ? (
          <Text style={{ color: "white" }}>{me.restaurant_name}</Text>
        ) : (
          <MyCart />
        )}
        <Button shape="round" onClick={onLogout}>
          Logout
        </Button>
      </Space>
    );
    content = isMerchant ? <MerchantDashboard /> : <FoodList />;
  }

  return (
    <Layout style={{ height: "100vh" }}>
      <Header>
        <div className="header" style={{ display: "flex", justifyContent: "space-between" }}>
          <Title
            level={2}
            style={{ color: "white", lineHeight: "inherit", marginBottom: 0 }}
          >
            {isMerchant ? "Spring Food · Merchant" : "Spring Food"}
          </Title>
          <div>{headerRight}</div>
        </div>
      </Header>
      <Content
        style={{
          padding: "50px",
          maxHeight: "calc(100% - 64px)",
          overflowY: "auto",
        }}
      >
        {content}
      </Content>
    </Layout>
  );
}

export default App;
