import { Table, Tag, Typography } from "antd";

const { Text } = Typography;

const STATUS_COLORS = { PAID: "orange", ACCEPTED: "blue", DONE: "green", CANCELLED: "default" };

// 餐厅都在湾区，时间统一按太平洋时间显示，和后端按日期搜索的口径一致
export const formatTime = (iso) =>
  new Date(iso).toLocaleString("en-US", {
    timeZone: "America/Los_Angeles",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });

// 订单表格，活跃订单和搜索结果共用；renderActions 为空时不显示操作列
const OrderTable = ({ orders, loading, renderActions }) => {
  const columns = [
    { title: "Order", dataIndex: "id", width: 90, render: (id) => `#${id}` },
    { title: "Placed at (PT)", dataIndex: "created_at", width: 160, render: formatTime },
    {
      title: "Customer",
      key: "customer",
      width: 170,
      render: (_, o) => (
        <>
          <div>{o.customer_name || "-"}</div>
          <Text type="secondary">{o.customer_phone || "-"}</Text>
        </>
      ),
    },
    {
      title: "Items",
      dataIndex: "lines",
      render: (lines) =>
        lines.map((l, i) => (
          <div key={i}>
            {l.name} <Text type="secondary">× {l.quantity}</Text>
          </div>
        )),
    },
    { title: "Total", dataIndex: "total_price", width: 90, render: (p) => `$${Number(p).toFixed(2)}` },
    {
      title: "Status",
      dataIndex: "status",
      width: 110,
      render: (s) => <Tag color={STATUS_COLORS[s]}>{s}</Tag>,
    },
  ];
  if (renderActions) {
    columns.push({ title: "Actions", key: "actions", width: 190, render: (_, o) => renderActions(o) });
  }

  return <Table rowKey="id" columns={columns} dataSource={orders} loading={loading} pagination={false} />;
};

export default OrderTable;
