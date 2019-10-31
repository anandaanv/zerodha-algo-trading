package com.dtech.kitecon.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class JDBCDataManager {

	public void readRecords()
	{
		try{  
				Class.forName("com.mysql.cj.jdbc.Driver");  
				System.out.println("forName done");

				Connection con=DriverManager.getConnection(  
				"jdbc:mysql://localhost:3306/algotrading","root","password");
				System.out.println("connection done");

				Statement stmt=con.createStatement();
				String schema = con.getSchema();
				System.out.println("Schema :" + schema );

				ResultSet rs=stmt.executeQuery("select * from BaseCandle");  
				while(rs.next())  
				System.out.println(rs.getString(1));  
				con.close();  
			}
		catch(Exception e)
		{ 
			System.out.println(e);
		}  
	}  
}