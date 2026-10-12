package com.github.tropin.ridermcp.ide

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlReadOnlyGuardTest {

    private fun allowed(sql: String, tsql: Boolean = true) =
        SqlReadOnlyGuard.check(sql, tsql).let { assertTrue(it.allowed, "expected allowed: $sql → ${it.violations}") }

    private fun rejected(sql: String, tsql: Boolean = true) =
        SqlReadOnlyGuard.check(sql, tsql).let { assertFalse(it.allowed, "expected rejected: $sql") }

    @Test
    fun `plain selects and DMV queries pass`() {
        allowed("select top 3 * from dbo.instances where name like '%update%'")
        allowed("select * from sys.dm_exec_requests r cross apply sys.dm_exec_sql_text(r.sql_handle)")
        allowed("select replace(name, 'a', 'b'), last_user_update from sys.dm_db_index_usage_stats")
        allowed("select [delete], \"insert\" from t")
        allowed("-- update t set x = 1\nselect 1 /* drop table t */")
        allowed("select * from a inner merge join b on a.id = b.id option (merge join)")
    }

    @Test
    fun `DML and DDL on real tables are rejected`() {
        rejected("update instances set name = 'x'")
        rejected("delete from dbo.instances where id = 1")
        rejected("insert into dbo.t values (1)")
        rejected("insert dbo.t select 1")
        rejected("merge into dbo.t using s on 1=1 when matched then delete;")
        rejected("truncate table dbo.t")
        rejected("drop table dbo.t")
        rejected("alter table dbo.t add c int")
        rejected("create table dbo.t (id int)")
        rejected("create index ix on dbo.t(id)")
        rejected("select * into dbo.copy from dbo.t")
        rejected("grant select on t to u")
        rejected("backup database x to disk = 'c:\\x.bak'")
        rejected("kill 55")
        rejected("with c as (select * from t) update c set x = 1")
    }

    @Test
    fun `session objects are allowed`() {
        allowed("select name into #t from sys.databases; insert into #t values ('x'); update #t set name = 'y'; delete from #t; drop table #t")
        allowed("declare @t table (id int); insert @t select 1; update @t set id = 2; delete @t")
        allowed("create table #r (db sysname); create index ix on #r(db); truncate table #r; alter table #r add n int; drop table if exists #r, #s")
        allowed("insert into tempdb..#t select 1")
        allowed("declare c cursor local fast_forward for select name from sys.databases; open c; fetch next from c into @db; close c; deallocate c")
        allowed("create temp table t as select 1", tsql = false)
        allowed("select * into temp t from x", tsql = false)
    }

    @Test
    fun `transaction escapes are rejected`() {
        rejected("select 1; commit")
        rejected("set implicit_transactions off")
        rejected("set transaction read write", tsql = false)
        rejected("start transaction read write", tsql = false)
        rejected("set default_transaction_read_only = off", tsql = false)
        allowed("set transaction isolation level read uncommitted; select 1")
        allowed("begin tran; select 1; rollback")
    }

    @Test
    fun `exec allows read-only system procedures only`() {
        allowed("exec sp_who2")
        allowed("exec sys.sp_helptext 'dbo.p'")
        allowed("exec master..sp_spaceused")
        allowed("sp_who")
        rejected("exec dbo.usp_cleanup")
        rejected("exec sp_rename 'a', 'b'")
        rejected("exec sp_configure 'x', 1")
        rejected("exec @proc")
        rejected("execute as login = 'sa'")
    }

    @Test
    fun `literal dynamic SQL is checked recursively`() {
        allowed("exec sp_executesql N'select * from sys.tables where name = @n', N'@n sysname', @n = N'x'")
        rejected("exec sp_executesql N'update t set x = 1'")
        allowed("exec('select 1')")
        rejected("exec('drop table t')")
        rejected("exec sp_MSforeachdb 'use ?; delete from t'")
        allowed("exec sp_MSforeachdb 'use ?; select db_name()'")
    }

    @Test
    fun `dynamic SQL built from variables is rejected (fail closed)`() {
        rejected("declare @sql nvarchar(max) = N'select 1'; exec sp_executesql @sql")
        rejected("declare @c nvarchar(10) = 'COM' + 'MIT'; exec(@c)")
        rejected("exec(N'select * from ' + quotename(@db) + '.sys.tables')")
        rejected("exec sp_MSforeachdb @cmd")
        allowed("exec sp_MSforeachdb 'select ''?'', count(*) from [?].sys.tables'")
    }

    @Test
    fun `postgres side effects are rejected`() {
        rejected("do \$\$ begin delete from t; end \$\$", tsql = false)
        rejected("select * from t for update", tsql = false)
        rejected("vacuum t", tsql = false)
        rejected("copy t from '/tmp/x'", tsql = false)
        allowed("select \$\$update t\$\$", tsql = false)
        allowed("select load, cluster from servers order by load", tsql = false)
    }
}
