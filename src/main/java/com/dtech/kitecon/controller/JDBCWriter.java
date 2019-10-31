package com.dtech.kitecon.controller;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;

public class JDBCWriter {

	public static void main(String[] args) throws ParseException {
//		JDBCDataManager manager = new JDBCDataManager();
//
//		manager.readRecords();

		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		dateFormat.parse("2019-10-25T15:15:00+0530");
	}

}
