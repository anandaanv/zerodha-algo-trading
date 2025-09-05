#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.ticker import MaxNLocator
import json

# Import the functions from matplotlib_chart.py
from matplotlib_chart import add_rsi_indicator, add_macd_indicator, add_stochastic_indicator, add_adx_indicator

# Create test data
def create_test_data(n=100):
    """Create sample OHLCV data for testing"""
    np.random.seed(42)  # For reproducibility
    
    # Generate dates
    base = pd.Timestamp('2023-01-01')
    dates = pd.date_range(base, periods=n)
    
    # Generate price data with a trend and some randomness
    close = np.linspace(100, 130, n) + np.random.normal(0, 5, n)
    
    # Add a price pattern
    close[30:50] += 15  # Create a peak
    close[60:80] -= 10  # Create a trough
    
    # Generate other OHLC data based on close prices
    high = close + np.random.uniform(0, 3, n)
    low = close - np.random.uniform(0, 3, n)
    open_price = close - np.random.uniform(-2, 2, n)
    
    # Generate volume
    volume = np.random.uniform(1000, 5000, n)
    volume[30:50] *= 2  # Higher volume during peak
    volume[60:80] *= 1.5  # Higher volume during trough
    
    # Create DataFrame
    data = pd.DataFrame({
        'date': dates,
        'open': open_price,
        'high': high,
        'low': low,
        'close': close,
        'volume': volume
    })
    
    return data

def test_indicators():
    """Test the indicator functions with separate panels"""
    # Create test data
    data = create_test_data()
    
    # Create figure with subplots for each indicator
    fig = plt.figure(figsize=(12, 16))
    
    # Create gridspec with appropriate height ratios
    gs = fig.add_gridspec(4, 1, height_ratios=[1, 1, 1, 1])
    
    # Create axes for each indicator
    ax_rsi = fig.add_subplot(gs[0])
    ax_macd = fig.add_subplot(gs[1])
    ax_stoch = fig.add_subplot(gs[2])
    ax_adx = fig.add_subplot(gs[3])
    
    # Add indicators
    add_rsi_indicator(ax_rsi, data, {'period': 14})
    add_macd_indicator(ax_macd, data, {'fastPeriod': 12, 'slowPeriod': 26, 'signalPeriod': 9})
    add_stochastic_indicator(ax_stoch, data, {'kPeriod': 14, 'dPeriod': 3, 'slowing': 3})
    add_adx_indicator(ax_adx, data, {'period': 14})
    
    # Set titles
    ax_rsi.set_title('RSI Indicator')
    ax_macd.set_title('MACD Indicator')
    ax_stoch.set_title('Stochastic Oscillator')
    ax_adx.set_title('ADX Indicator')
    
    # Format x-axis dates (only show on bottom panel)
    for ax in [ax_rsi, ax_macd, ax_stoch]:
        plt.setp(ax.get_xticklabels(), visible=False)
    
    ax_adx.xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))
    plt.setp(ax_adx.get_xticklabels(), rotation=45)
    
    # Adjust layout and save
    plt.tight_layout()
    
    # Create output directory if it doesn't exist
    output_dir = 'test_output'
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    # Save the chart
    output_file = os.path.join(output_dir, 'test_indicators.png')
    plt.savefig(output_file, dpi=300)
    plt.close(fig)
    
    print(f"Test chart saved to: {output_file}")
    return output_file

if __name__ == "__main__":
    test_indicators()