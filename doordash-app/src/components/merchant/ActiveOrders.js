import { Button, message, Popconfirm, Space, Typography } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useState } from "react";
import { getActiveOrders, updateOrderStatus } from "../../utils";
import OrderTable from "./OrderTable";

const { Text } = Typography;

const REFRESH_MS = 15000;

// 待处理订单：待接单（PAID）可以接单 / 拒单，已接单（ACCEPTED）可以标记完成。每 15 秒自动刷新，看到新下的单
const ActiveOrders = () => {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(false);
  const [acting, setActing] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return getActiveOrders()
      .then(setOrders)
      .catch((err) => message.error(err.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const act = (orderId, action) => {
    setActing(orderId);
    updateOrderStatus(orderId, action)
      .then(() => message.success(`Order #${orderId}: ${action} done`))
      .catch((err) => message.warning(err.message))
      .finally(() => {
        setActing(null);
        load();
      });
  };

  const renderActions = (o) =>
    o.status === "PAID" ? (
      <Space>
        <Button type="primary" size="small" loading={acting === o.id} onClick={() => act(o.id, "accept")}>
          Accept
        </Button>
        <Popconfirm title="Reject this order?" onConfirm={() => act(o.id, "reject")}>
          <Button danger size="small" disabled={acting === o.id}>
            Reject
          </Button>
        </Popconfirm>
      </Space>
    ) : (
      <Button size="small" loading={acting === o.id} onClick={() => act(o.id, "complete")}>
        Mark done
      </Button>
    );

  const paid = orders.filter((o) => o.status === "PAID").length;

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Text>
          {paid} waiting to accept, {orders.length - paid} in progress
        </Text>
        <Button icon={<ReloadOutlined />} onClick={load}>
          Refresh
        </Button>
      </Space>
      <OrderTable orders={orders} loading={loading} renderActions={renderActions} />
    </>
  );
};

export default ActiveOrders;
