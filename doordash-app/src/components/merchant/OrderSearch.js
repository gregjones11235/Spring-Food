import { Button, DatePicker, Form, Input, message, Select, Space, Typography } from "antd";
import { useState } from "react";
import { searchMerchantOrders } from "../../utils";
import OrderTable from "./OrderTable";

const { Text } = Typography;

const PAGE_SIZE = 20;

// 订单搜索：日期 / 顾客手机号（完整、开头、结尾）/ 菜名 / 状态，结果按下单时间倒序，"加载更多"翻页。
// 每次查询显示耗时，方便对比 SQL_and_Redis_lab.md Q3 里加索引、改写法前后的效果
const OrderSearch = () => {
  const [form] = Form.useForm();
  const [criteria, setCriteria] = useState(null);
  const [orders, setOrders] = useState([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [elapsed, setElapsed] = useState(null);

  const run = (nextCriteria, nextPage) => {
    setLoading(true);
    const start = performance.now();
    searchMerchantOrders(nextCriteria, nextPage, PAGE_SIZE)
      .then((result) => {
        setElapsed(Math.round(performance.now() - start));
        setOrders((prev) => (nextPage === 1 ? result.items : [...prev, ...result.items]));
        setHasMore(result.has_more);
        setCriteria(nextCriteria);
        setPage(nextPage);
      })
      .catch((err) => message.error(err.message))
      .finally(() => setLoading(false));
  };

  const onFinish = (values) => {
    run(
      {
        date: values.date ? values.date.format("YYYY-MM-DD") : null,
        phone: values.phone?.trim(),
        phoneMatch: values.phoneMatch,
        dish: values.dish?.trim(),
        statuses: values.statuses,
      },
      1
    );
  };

  return (
    <>
      <Form
        form={form}
        layout="inline"
        onFinish={onFinish}
        initialValues={{ phoneMatch: "exact", statuses: [] }}
        style={{ marginBottom: 16, rowGap: 12 }}
      >
        <Form.Item name="date" label="Date (PT)">
          <DatePicker />
        </Form.Item>
        <Form.Item label="Phone">
          <Input.Group compact>
            <Form.Item name="phoneMatch" noStyle>
              <Select style={{ width: 110 }}>
                <Select.Option value="exact">Exact</Select.Option>
                <Select.Option value="prefix">Starts with</Select.Option>
                <Select.Option value="suffix">Ends with</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="phone" noStyle rules={[{ pattern: /^[\d\s()+-]*$/, message: "Digits only" }]}>
              <Input style={{ width: 150 }} placeholder="e.g. 415" allowClear />
            </Form.Item>
          </Input.Group>
        </Form.Item>
        <Form.Item name="dish" label="Dish">
          <Input placeholder="e.g. whopper" allowClear />
        </Form.Item>
        <Form.Item name="statuses" label="Status">
          <Select mode="multiple" allowClear placeholder="Any" style={{ minWidth: 160 }}>
            {["PAID", "ACCEPTED", "DONE", "CANCELLED"].map((s) => (
              <Select.Option key={s} value={s}>
                {s}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" loading={loading && page === 1}>
              Search
            </Button>
            <Button onClick={() => form.resetFields()}>Reset</Button>
          </Space>
        </Form.Item>
      </Form>
      {criteria && (
        <Text type="secondary" style={{ display: "block", marginBottom: 8 }}>
          {orders.length} orders shown{hasMore ? " (more available)" : ""} · last query {elapsed} ms
        </Text>
      )}
      <OrderTable orders={orders} loading={loading} />
      {hasMore && (
        <div style={{ textAlign: "center", marginTop: 16 }}>
          <Button loading={loading} onClick={() => run(criteria, page + 1)}>
            Load more
          </Button>
        </div>
      )}
    </>
  );
};

export default OrderSearch;
