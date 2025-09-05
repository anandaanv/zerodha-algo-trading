#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import os
import json
import traceback

# Check for required dependencies
dependencies_available = True
missing_dependencies = []

try:
    from py4j.java_gateway import JavaGateway, GatewayParameters, CallbackServerParameters
except ImportError:
    dependencies_available = False
    missing_dependencies.append("py4j")

try:
    import pandas as pd
except ImportError:
    dependencies_available = False
    missing_dependencies.append("pandas")

try:
    import numpy as np
except ImportError:
    dependencies_available = False
    missing_dependencies.append("numpy")

try:
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates
    from matplotlib.ticker import MaxNLocator
    # Configure matplotlib for non-interactive backend for better performance
    plt.switch_backend('Agg')
except ImportError:
    dependencies_available = False
    missing_dependencies.append("matplotlib")

# Print dependency status
if not dependencies_available:
    print(f"WARNING: Missing required Python dependencies: {', '.join(missing_dependencies)}")
    print("Chart generation will not work properly without these dependencies.")
    print("Please install them using: pip install matplotlib pandas numpy py4j mplfinance")

def connect_to_gateway(port):
    """Connect to the Java gateway server"""
    gateway = JavaGateway(
        gateway_parameters=GatewayParameters(port=port),
        callback_server_parameters=CallbackServerParameters(port=0)
    )
    return gateway

def create_chart(data_file, config_file, output_file):
    """Create a chart using matplotlib based on the provided data and configuration"""
    # Check if all required dependencies are available
    if not dependencies_available:
        print(f"ERROR: Cannot create chart due to missing dependencies: {', '.join(missing_dependencies)}")
        print(f"Input files: data_file={data_file}, config_file={config_file}, output_file={output_file}")
        # Create a minimal valid PNG file to indicate error
        create_error_image(output_file)
        return False
    
    try:
        # Check if we have multiple data files and output files
        data_files = data_file.split(',') if ',' in data_file else [data_file]
        output_files = output_file.split(',') if ',' in output_file else [output_file]
        
        # Load config
        with open(config_file, 'r') as f:
            config = json.load(f)
            
        # Check if we have dataFiles in config
        if 'dataFiles' in config:
            # Generate charts for each data file
            for i, data_file_info in enumerate(config['dataFiles']):
                if i < len(output_files):
                    # Load data for this file
                    data = pd.read_csv(data_file_info['path'], parse_dates=['date'])
                    
                    # Create a title with symbol and interval
                    chart_title = f"{data_file_info['symbol']} - {data_file_info['interval']}"
                    
                    # Generate the chart
                    generate_single_chart(data, config, chart_title, output_files[i])
            
            return True
        else:
            # Legacy mode - single chart
            data = pd.read_csv(data_files[0], parse_dates=['date'])
        
        # For legacy mode, use the original title from config
        chart_title = config.get('title', 'Chart')
        return generate_single_chart(data, config, chart_title, output_files[0])
        
    except Exception as e:
        print(f"ERROR: Failed to create chart: {str(e)}")
        traceback.print_exc()
        return False

def generate_single_chart(data, config, chart_title, output_file):
    """Generate a single chart with the given data and configuration"""
    try:
        # Count how many indicator panels we need
        indicator_panels = 0
        if 'indicators' in config:
            for indicator in config['indicators']:
                indicator_type = indicator.get('type', '').upper()
                if indicator_type in ['RSI', 'MACD', 'STOCHASTIC', 'ADX']:
                    indicator_panels += 1
        
        # Determine total number of panels needed
        total_panels = 1  # Price panel
        if config.get('showVolume', False):
            total_panels += 1
        total_panels += indicator_panels
        
        # Create figure with appropriate height and width - TradingView style
        panel_height = 2.2  # Height per panel in inches (increased for better spacing)
        fig_height = panel_height * total_panels + 1.2  # +1.2 for margins
        fig_width = 16  # Wider figure for better spacing and TradingView-like appearance
        fig = plt.figure(figsize=(fig_width, fig_height), dpi=120)  # Higher DPI for sharper appearance
    
        # Set figure background to white for clean TradingView-like appearance
        fig.patch.set_facecolor('white')
    
        # Create gridspec with appropriate height ratios and spacing
        # Price panel gets more space, indicators get equal smaller space
        height_ratios = [4.0]  # Price panel (increased ratio for TradingView-like appearance)
        if config.get('showVolume', False):
            height_ratios.append(0.8)  # Volume panel (reduced for TradingView-like appearance)
        for _ in range(indicator_panels):
            height_ratios.append(1.0)  # Indicator panels (slightly reduced for TradingView-like appearance)
        
        gs = fig.add_gridspec(total_panels, 1, height_ratios=height_ratios, hspace=0.15)  # Reduced spacing between panels
        
        # Create price panel
        ax_price = fig.add_subplot(gs[0])
        
        # Set the chart title
        ax_price.set_title(chart_title, fontsize=14, fontweight='bold')
        
        # Plot chart based on chart type
        chart_type = config.get('chartType', 'CANDLESTICK')
        
        if chart_type == 'CANDLESTICK':
            plot_candlestick_chart(ax_price, data, config)
        elif chart_type == 'LINE':
            plot_line_chart(ax_price, data, config)
        elif chart_type == 'BAR':
            plot_bar_chart(ax_price, data, config)
        else:
            # Default to candlestick
            plot_candlestick_chart(ax_price, data, config)
        
        # Add overlay indicators (SMA, EMA, Bollinger Bands)
        if 'indicators' in config:
            add_overlay_indicators(ax_price, data, config['indicators'])
        
        # Format price axes
        ax_price.set_title(config.get('title', 'Price Chart'))
        ax_price.set_ylabel('Price')
        ax_price.grid(True, alpha=0.3)

        # Create volume panel if requested
        panel_index = 1
        if config.get('showVolume', False):
            ax_volume = fig.add_subplot(gs[panel_index], sharex=ax_price)
            plot_volume(ax_volume, data)
            panel_index += 1

        # Create indicator panels
        indicator_axes = {}
        if 'indicators' in config:
            for indicator in config['indicators']:
                indicator_type = indicator.get('type', '').upper()
                if indicator_type in ['RSI', 'MACD', 'STOCHASTIC', 'ADX']:
                    # Create a new panel for this indicator
                    ax_indicator = fig.add_subplot(gs[panel_index], sharex=ax_price)
                    indicator_axes[indicator_type] = ax_indicator
                    panel_index += 1

        # Add panel indicators
        if 'indicators' in config and indicator_axes:
            add_panel_indicators(indicator_axes, data, config['indicators'])

        # Format x-axis dates (only show on bottom panel)
        for ax in fig.axes[:-1]:
            plt.setp(ax.get_xticklabels(), visible=False)

        fig.axes[-1].xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))
        plt.setp(fig.axes[-1].get_xticklabels(), rotation=45)

        # Add legend if requested
        if config.get('showLegend', True):
            ax_price.legend()

        # Adjust layout and save with high quality
        plt.tight_layout()
        plt.savefig(output_file, dpi=300, bbox_inches='tight', pad_inches=0.2, facecolor='white', edgecolor='none')
        plt.close(fig)
        
        print(f"Successfully created chart: {output_file}")
        return True
    except Exception as e:
        print(f"ERROR: Failed to create chart: {e}")
        traceback.print_exc()
        # Create a minimal valid PNG file to indicate error
        create_error_image(output_file)
        return False

def create_error_image(output_file):
    """Create a minimal valid PNG file to indicate an error occurred"""
    # Minimal valid PNG file (1x1 pixel)
    png_data = bytes([
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  # PNG signature
        0x00, 0x00, 0x00, 0x0D,                          # IHDR chunk length
        0x49, 0x48, 0x44, 0x52,                          # IHDR chunk type
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,  # Width & height (1x1)
        0x08, 0x00, 0x00, 0x00, 0x00,                    # Bit depth, color type, etc.
        0x37, 0x6E, 0xF9, 0x24                           # IHDR CRC
    ])
    
    try:
        with open(output_file, 'wb') as f:
            f.write(png_data)
        print(f"Created error indicator image: {output_file}")
    except Exception as e:
        print(f"Failed to create error indicator image: {e}")

def plot_candlestick_chart(ax, data, config):
    """Plot a candlestick chart with TradingView-like styling"""
    from mplfinance.original_flavor import candlestick_ohlc

    # Convert data to OHLC format
    ohlc = data[['date', 'open', 'high', 'low', 'close']].copy()
    ohlc['date'] = ohlc['date'].map(mdates.date2num)

    # Calculate optimal width based on data density
    # TradingView adjusts candlestick width based on zoom level
    data_range = len(data)
    optimal_width = min(0.8, max(0.2, 6.0/data_range))

    # Use more professional colors like TradingView
    up_color = config.get('upColor', '#26a69a')    # Teal green like TradingView
    down_color = config.get('downColor', '#ef5350')  # Soft red like TradingView

    # Plot candlestick chart with optimized width to prevent overlapping
    candlestick_ohlc(ax, ohlc.values, width=optimal_width,
                    colorup=up_color,
                    colordown=down_color,
                    alpha=1.0)  # Full opacity for professional look

    # Set background and grid style like TradingView
    ax.set_facecolor('#ffffff')  # White background
    ax.grid(True, linestyle='--', linewidth=0.5, alpha=0.3, color='#666666')  # Subtle grid

def plot_line_chart(ax, data, config):
    """Plot a line chart of close prices"""
    ax.plot(data['date'], data['close'], label='Close Price')

def plot_bar_chart(ax, data, config):
    """Plot a bar chart (OHLC)"""
    # Calculate bar width
    width = 0.8
    
    # Plot bars
    for i, row in data.iterrows():
        # Determine color based on price movement
        color = config.get('upColor', 'green') if row['close'] >= row['open'] else config.get('downColor', 'red')
        
        # Plot OHLC bar
        ax.plot([row['date'], row['date']], [row['low'], row['high']], color=color)
        ax.plot([row['date'], row['date'] - pd.Timedelta(width/2, 'D')], [row['open'], row['open']], color=color)
        ax.plot([row['date'], row['date'] + pd.Timedelta(width/2, 'D')], [row['close'], row['close']], color=color)

def plot_volume(ax, data):
    """Plot volume bars"""
    # Determine colors based on price movement
    colors = ['green' if data.iloc[i]['close'] >= data.iloc[i]['open'] else 'red' for i in range(len(data))]

    # Plot volume bars
    ax.bar(data['date'], data['volume'], color=colors, alpha=0.5)
    ax.set_ylabel('Volume')
    ax.grid(True, alpha=0.3)

    # Format y-axis to show fewer ticks for better readability
    ax.yaxis.set_major_locator(MaxNLocator(nbins=5))

def add_overlay_indicators(ax, data, indicators):
    """Add technical indicators that overlay on the price chart"""
    for indicator in indicators:
        indicator_type = indicator.get('type', '').upper()
        
        if indicator_type == 'SMA':
            add_sma_indicator(ax, data, indicator)
        elif indicator_type == 'EMA':
            add_ema_indicator(ax, data, indicator)
        elif indicator_type == 'BOLLINGER':
            add_bollinger_bands(ax, data, indicator)

def add_panel_indicators(indicator_axes, data, indicators):
    """Add technical indicators in separate panels"""
    for indicator in indicators:
        indicator_type = indicator.get('type', '').upper()
        
        if indicator_type == 'RSI' and 'RSI' in indicator_axes:
            add_rsi_indicator(indicator_axes['RSI'], data, indicator)
        elif indicator_type == 'MACD' and 'MACD' in indicator_axes:
            add_macd_indicator(indicator_axes['MACD'], data, indicator)
        elif indicator_type == 'STOCHASTIC' and 'STOCHASTIC' in indicator_axes:
            add_stochastic_indicator(indicator_axes['STOCHASTIC'], data, indicator)
        elif indicator_type == 'ADX' and 'ADX' in indicator_axes:
            add_adx_indicator(indicator_axes['ADX'], data, indicator)

def add_sma_indicator(ax, data, indicator):
    """Add Simple Moving Average indicator with TradingView-like styling"""
    period = indicator.get('period', 20)
    
    # TradingView-like colors based on common SMA periods
    tv_colors = {
        9: '#FF6D00',   # Orange
        20: '#2962FF',  # Blue
        50: '#6200EA',  # Purple
        100: '#00C853', # Green
        200: '#DD2C00'  # Red
    }
    
    # Choose color based on period or use default
    color = tv_colors.get(period, indicator.get('color', '#2962FF'))
    
    # Calculate SMA
    data[f'SMA_{period}'] = data['close'].rolling(window=period).mean()
    
    # Plot SMA with TradingView-like styling
    ax.plot(data['date'], data[f'SMA_{period}'], 
            label=f'SMA {period}', 
            color=color, 
            linewidth=1.0,  # Thinner line like TradingView
            alpha=0.9)      # Slightly transparent

def add_ema_indicator(ax, data, indicator):
    """Add Exponential Moving Average indicator with TradingView-like styling"""
    period = indicator.get('period', 20)
    
    # TradingView-like colors based on common EMA periods
    tv_colors = {
        9: '#FF6D00',   # Orange
        12: '#FF6D00',  # Orange
        20: '#2962FF',  # Blue
        26: '#2962FF',  # Blue
        50: '#6200EA',  # Purple
        100: '#00C853', # Green
        200: '#DD2C00'  # Red
    }
    
    # Choose color based on period or use default
    color = tv_colors.get(period, indicator.get('color', '#FF6D00'))
    
    # Calculate EMA
    data[f'EMA_{period}'] = data['close'].ewm(span=period, adjust=False).mean()
    
    # Plot EMA with TradingView-like styling
    ax.plot(data['date'], data[f'EMA_{period}'], 
            label=f'EMA {period}', 
            color=color, 
            linewidth=1.0,  # Thinner line like TradingView
            alpha=0.9)      # Slightly transparent

def add_bollinger_bands(ax, data, indicator):
    """Add Bollinger Bands indicator with TradingView-like styling"""
    period = indicator.get('period', 20)
    std_dev = indicator.get('std_dev', 2)
    
    # Calculate Bollinger Bands
    data[f'SMA_{period}'] = data['close'].rolling(window=period).mean()
    data[f'BOLL_STD'] = data['close'].rolling(window=period).std()
    data[f'BOLL_UPPER'] = data[f'SMA_{period}'] + (data[f'BOLL_STD'] * std_dev)
    data[f'BOLL_LOWER'] = data[f'SMA_{period}'] - (data[f'BOLL_STD'] * std_dev)
    
    # Plot Bollinger Bands with TradingView-like styling
    # TradingView uses solid lines with subtle colors
    ax.plot(data['date'], data[f'BOLL_UPPER'], linestyle='-', linewidth=1.0, alpha=0.5,
            label='Upper Band', color='#2962FF')  # TradingView-like blue
    ax.plot(data['date'], data[f'SMA_{period}'], linewidth=1.0, alpha=0.7,
            label=f'SMA {period}', color='#B71C1C')  # TradingView-like red
    ax.plot(data['date'], data[f'BOLL_LOWER'], linestyle='-', linewidth=1.0, alpha=0.5,
            label='Lower Band', color='#2962FF')  # TradingView-like blue

def add_rsi_indicator(ax, data, indicator):
    """Add Relative Strength Index indicator with TradingView-like styling"""
    period = indicator.get('period', 14)
    
    # Calculate RSI
    delta = data['close'].diff()
    gain = delta.where(delta > 0, 0)
    loss = -delta.where(delta < 0, 0)
    
    avg_gain = gain.rolling(window=period).mean()
    avg_loss = loss.rolling(window=period).mean()
    
    rs = avg_gain / avg_loss
    data['RSI'] = 100 - (100 / (1 + rs))
    
    # Plot RSI on its own panel with TradingView-like styling
    ax.plot(data['date'], data['RSI'], 
            color='#2962FF',  # TradingView-like blue
            label=f'RSI ({period})', 
            linewidth=1.0)
    
    # Set panel properties like TradingView
    ax.set_ylim(0, 100)
    ax.set_ylabel('RSI', fontsize=9)
    ax.tick_params(axis='y', labelsize=8)
    
    # Add horizontal lines at 30 and 70 with TradingView-like styling
    ax.axhline(y=30, color='#787B86', linestyle='-', alpha=0.3, linewidth=0.5)
    ax.axhline(y=70, color='#787B86', linestyle='-', alpha=0.3, linewidth=0.5)
    
    # Add a middle line at 50 like TradingView
    ax.axhline(y=50, color='#787B86', linestyle='-', alpha=0.15, linewidth=0.5)
    
    # Set background color like TradingView
    ax.set_facecolor('#ffffff')
    
    # Add subtle grid like TradingView
    ax.grid(True, linestyle='--', linewidth=0.5, alpha=0.2, color='#666666')
    
    # Add legend with TradingView-like styling
    ax.legend(loc='upper right', fontsize='x-small', framealpha=0.5)

def add_macd_indicator(ax, data, indicator):
    """Add MACD indicator with TradingView-like styling"""
    fast_period = indicator.get('fastPeriod', 12)
    slow_period = indicator.get('slowPeriod', 26)
    signal_period = indicator.get('signalPeriod', 9)
    
    # Calculate MACD
    data['EMA_fast'] = data['close'].ewm(span=fast_period, adjust=False).mean()
    data['EMA_slow'] = data['close'].ewm(span=slow_period, adjust=False).mean()
    data['MACD'] = data['EMA_fast'] - data['EMA_slow']
    data['MACD_signal'] = data['MACD'].ewm(span=signal_period, adjust=False).mean()
    data['MACD_histogram'] = data['MACD'] - data['MACD_signal']
    
    # Plot MACD on its own panel with TradingView-like styling
    ax.plot(data['date'], data['MACD'],
            color='#2962FF',  # TradingView-like blue
            label=f'MACD ({fast_period},{slow_period},{signal_period})',
            linewidth=1.0)
    ax.plot(data['date'], data['MACD_signal'],
            color='#FF6D00',  # TradingView-like orange
            label='Signal',
            linewidth=1.0)

    # Calculate optimal width based on data density for histogram
    data_range = len(data)
    optimal_width = min(0.8, max(0.2, 6.0/data_range))

    # Plot histogram with TradingView-like styling
    # TradingView uses color based on the histogram value, not the change
    for i, row in data.iterrows():
        if i > 0:  # Skip first row as we need previous value
            # TradingView-like colors
            if row['MACD_histogram'] > 0:
                color = '#26a69a'  # Green for positive values
                # Lighter green for decreasing but still positive
                if row['MACD_histogram'] < data.iloc[i-1]['MACD_histogram']:
                    color = '#4db6ac'
            else:
                color = '#ef5350'  # Red for negative values
                # Lighter red for increasing but still negative
                if row['MACD_histogram'] > data.iloc[i-1]['MACD_histogram']:
                    color = '#e57373'

            ax.bar(row['date'], row['MACD_histogram'],
                   width=optimal_width * 0.8,  # Slightly narrower than candlesticks
                   color=color,
                   alpha=0.7)  # More visible than before
    
    # Set panel properties like TradingView
    ax.set_ylabel('MACD', fontsize=9)
    ax.tick_params(axis='y', labelsize=8)
    
    # Add zero line with TradingView-like styling
    ax.axhline(y=0, color='#787B86', linestyle='-', alpha=0.3, linewidth=0.5)
    
    # Set background color like TradingView
    ax.set_facecolor('#ffffff')
    
    # Add subtle grid like TradingView
    ax.grid(True, linestyle='--', linewidth=0.5, alpha=0.2, color='#666666')
    
    # Add legend with TradingView-like styling
    ax.legend(loc='upper right', fontsize='x-small', framealpha=0.5)

def add_stochastic_indicator(ax, data, indicator):
    """Add Stochastic Oscillator indicator with TradingView-like styling"""
    k_period = indicator.get('kPeriod', 14)
    d_period = indicator.get('dPeriod', 3)
    slowing = indicator.get('slowing', 3)
    
    # Calculate %K (Fast Stochastic)
    # Formula: %K = (Current Close - Lowest Low) / (Highest High - Lowest Low) * 100
    low_min = data['low'].rolling(window=k_period).min()
    high_max = data['high'].rolling(window=k_period).max()
    
    # Handle division by zero
    range_diff = high_max - low_min
    range_diff = range_diff.replace(0, 1)  # Replace zeros with 1 to avoid division by zero
    
    data['STOCH_K'] = ((data['close'] - low_min) / range_diff) * 100
    
    # Apply slowing if specified (moving average of %K)
    if slowing > 1:
        data['STOCH_K'] = data['STOCH_K'].rolling(window=slowing).mean()
    
    # Calculate %D (Slow Stochastic) - SMA of %K
    data['STOCH_D'] = data['STOCH_K'].rolling(window=d_period).mean()
    
    # Plot Stochastic on its own panel with TradingView-like styling
    ax.plot(data['date'], data['STOCH_K'], 
            color='#2962FF',  # TradingView-like blue
            label=f'%K ({k_period})', 
            linewidth=1.0)
    ax.plot(data['date'], data['STOCH_D'], 
            color='#FF6D00',  # TradingView-like orange
            label=f'%D ({d_period})', 
            linewidth=1.0)
    
    # Set panel properties like TradingView
    ax.set_ylim(0, 100)
    ax.set_ylabel('Stoch', fontsize=9)  # TradingView uses shorter labels
    ax.tick_params(axis='y', labelsize=8)
    
    # Add horizontal lines at 20 and 80 with TradingView-like styling
    ax.axhline(y=20, color='#787B86', linestyle='-', alpha=0.3, linewidth=0.5)
    ax.axhline(y=80, color='#787B86', linestyle='-', alpha=0.3, linewidth=0.5)
    
    # Add a middle line at 50 like TradingView
    ax.axhline(y=50, color='#787B86', linestyle='-', alpha=0.15, linewidth=0.5)
    
    # Set background color like TradingView
    ax.set_facecolor('#ffffff')
    
    # Add subtle grid like TradingView
    ax.grid(True, linestyle='--', linewidth=0.5, alpha=0.2, color='#666666')
    
    # Add legend with TradingView-like styling
    ax.legend(loc='upper right', fontsize='x-small', framealpha=0.5)

def add_adx_indicator(ax, data, indicator):
    """Add Average Directional Index (ADX) indicator with TradingView-like styling"""
    period = indicator.get('period', 14)
    
    # Calculate True Range (TR)
    data['TR'] = 0.0
    for i in range(1, len(data)):
        high_low = data.iloc[i]['high'] - data.iloc[i]['low']
        high_close = abs(data.iloc[i]['high'] - data.iloc[i-1]['close'])
        low_close = abs(data.iloc[i]['low'] - data.iloc[i-1]['close'])
        data.loc[data.index[i], 'TR'] = max(high_low, high_close, low_close)
    
    # Calculate Directional Movement (DM)
    data['DM+'] = 0.0
    data['DM-'] = 0.0
    
    for i in range(1, len(data)):
        up_move = data.iloc[i]['high'] - data.iloc[i-1]['high']
        down_move = data.iloc[i-1]['low'] - data.iloc[i]['low']
        
        if up_move > down_move and up_move > 0:
            data.loc[data.index[i], 'DM+'] = up_move
        else:
            data.loc[data.index[i], 'DM+'] = 0.0
            
        if down_move > up_move and down_move > 0:
            data.loc[data.index[i], 'DM-'] = down_move
        else:
            data.loc[data.index[i], 'DM-'] = 0.0
    
    # Calculate smoothed TR and DM
    data['ATR'] = data['TR'].rolling(window=period).mean()
    data['DM+_smooth'] = data['DM+'].rolling(window=period).mean()
    data['DM-_smooth'] = data['DM-'].rolling(window=period).mean()
    
    # Calculate Directional Indicators (DI)
    data['DI+'] = (data['DM+_smooth'] / data['ATR']) * 100
    data['DI-'] = (data['DM-_smooth'] / data['ATR']) * 100
    
    # Calculate Directional Index (DX)
    data['DX'] = (abs(data['DI+'] - data['DI-']) / (data['DI+'] + data['DI-'])) * 100
    
    # Calculate ADX (smoothed DX)
    data['ADX'] = data['DX'].rolling(window=period).mean()
    
    # Plot ADX on its own panel with TradingView-like styling
    ax.plot(data['date'], data['ADX'], 
            color='#7B1FA2',  # TradingView-like purple
            label=f'ADX ({period})', 
            linewidth=1.0)
    ax.plot(data['date'], data['DI+'], 
            color='#26a69a',  # TradingView-like green
            label='+DI', 
            linewidth=1.0)
    ax.plot(data['date'], data['DI-'], 
            color='#ef5350',  # TradingView-like red
            label='-DI', 
            linewidth=1.0)
    
    # Set panel properties like TradingView
    ax.set_ylim(0, 100)
    ax.set_ylabel('ADX', fontsize=9)
    ax.tick_params(axis='y', labelsize=8)
    
    # Add horizontal line at 25 with TradingView-like styling
    ax.axhline(y=25, color='#787B86', linestyle='-', alpha=0.3, linewidth=0.5)
    
    # Set background color like TradingView
    ax.set_facecolor('#ffffff')
    
    # Add subtle grid like TradingView
    ax.grid(True, linestyle='--', linewidth=0.5, alpha=0.2, color='#666666')
    
    # Add legend with TradingView-like styling
    ax.legend(loc='upper right', fontsize='x-small', framealpha=0.5)

class MatplotlibChartService:
    """
    Python service that handles chart creation requests from Java
    """

    def __init__(self):
        """Initialize the service and check dependencies"""
        self.dependencies_available = dependencies_available
        if not self.dependencies_available:
            print(f"WARNING: MatplotlibChartService initialized with missing dependencies: {', '.join(missing_dependencies)}")
            print("Chart generation will not work properly. Please install the required dependencies:")
            print("pip install matplotlib pandas numpy py4j mplfinance")
        else:
            print("MatplotlibChartService initialized successfully with all required dependencies")

    def _get_object_id(self):
           """
           Required by newer versions of py4j for object identification
           """
           return self.__class__.__module__ + "." + self.__class__.__name__

    def create_chart(self, data_file, config_file, output_file):
        """Create a chart and return success status"""
        try:
            print(f"Python service received chart creation request: data={data_file}, config={config_file}, output={output_file}")
            
            # Check if files exist
            if not os.path.exists(data_file):
                print(f"ERROR: Data file not found: {data_file}")
                create_error_image(output_file)
                return False
                
            if not os.path.exists(config_file):
                print(f"ERROR: Config file not found: {config_file}")
                create_error_image(output_file)
                return False
            
            # Create the chart
            result = create_chart(data_file, config_file, output_file)
            print(f"Chart creation result: {result}")
            return result
        except Exception as e:
            print(f"Error creating chart: {e}")
            traceback.print_exc()
            create_error_image(output_file)
            return False
            
    def getClass(self):
        """
        Required by py4j to properly identify this class
        """
        return self.__class__.__module__ + "." + self.__class__.__name__
        
    def getDependencyStatus(self):
        """
        Return the status of dependencies for debugging
        """
        if dependencies_available:
            return "All dependencies available"
        else:
            return f"Missing dependencies: {', '.join(missing_dependencies)}"

if __name__ == "__main__":
    # Check command line arguments
    if len(sys.argv) == 2 and sys.argv[1].isdigit():
        # Original gateway mode
        port = int(sys.argv[1])
        print(f"Running in gateway mode. Attempting to connect to Java Gateway on port {port}")
        
        try:
            gateway = connect_to_gateway(port)
            print(f"Successfully connected to Java Gateway on port {port}")
            
            # Create service instance
            service = MatplotlibChartService()
            print(f"Created MatplotlibChartService instance: {service}")
            
            # Register the Python service with the gateway
            print("Attempting to register Python service with Java gateway...")
            gateway.entry_point.registerPythonService(service)
            print("Successfully registered Python service with Java gateway")
            
            # Keep the process running
            try:
                print("Python service is now running and waiting for requests...")
                while True:
                    import time
                    time.sleep(1)
            except KeyboardInterrupt:
                print("Shutting down")
                gateway.shutdown()
        except Exception as e:
            print(f"ERROR: Failed to initialize Python service: {e}")
            import traceback
            traceback.print_exc()
            sys.exit(1)
    
    elif len(sys.argv) >= 3:
        # Direct execution mode with data, config, and output paths
        data_file = sys.argv[1]
        config_file = sys.argv[2]
        output_file = sys.argv[3]
        
        print(f"Running in direct execution mode with: data={data_file}, config={config_file}, output={output_file}")
        
        # Create the chart directly
        success = create_chart(data_file, config_file, output_file)
        
        # Exit with appropriate status code
        sys.exit(0 if success else 1)
    
    else:
        # Print usage information
        print("Usage:")
        print("  Gateway mode: python matplotlib_chart.py <gateway_port>")
        print("  Direct mode:  python matplotlib_chart.py <data_file> <config_file> <output_file>")
        print(f"Received {len(sys.argv)} arguments: {sys.argv}")
        sys.exit(1)