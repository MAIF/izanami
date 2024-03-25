//const pg = require("pg");

async function cleanDatabase() {
  /*const { Client } = pg;

  const client = new Client({
    user: "postgres",
    host: "localhost",
    database: "postgres",
    password: "postgres",
    port: 5432,
  });
  await client.connect();

  try {
    const res = await client.query("SELECT * FROM izanami.tenants");
    const tenants = res.rows.map((r) => r.name);

    tenants.forEach(async (tenant) => {
      await client.query(`DROP SCHEMA "${tenant}" CASCADE`);
    });

    await client.query("TRUNCATE TABLE izanami.tenants CASCADE");

    await client.query(
      "DELETE FROM izanami.users WHERE username != 'RESERVED_ADMIN_USER'"
    );
    await client.query("TRUNCATE TABLE izanami.users_tenants_rights CASCADE");
  } catch (err) {
    if (err.code === "42P01") {
      // Database is not initialized, nothing to do
    } else {
      throw err;
    }
  }

  await client.end();*/
  return Promise.resolve();
}

//init();
module.exports = {
  cleanDatabase,
};
